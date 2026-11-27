import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { IBAN_PL, get, makeWorld, put, rowsOf, worksSchedule, type World } from './support.js';
import { apiRows, loginAndOpenTab, pageText, uiLogin } from './ui-support.js';

// FOR-11-06 Part B (UI): B1 combined payments tab (staff) and B2 client cards. Requirements 2, 3, 4.
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-06-delivery-ux-refinements/test-cases.md.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()) in `finally`. Language PL (config).
// Fixtures: a MANUAL works schedule with three tranches (worksSchedule) and an issued materials payment
// (issuedPayment). The issue needs the PDF storage; when it is down the test that needs it is skipped at run time.

/** WORKS tranches + one MATERIALS payment (issued when the PDF storage works, otherwise a draft). */
async function paymentsFixture(w: World) {
  await w.acceptedPosition(2, 100);
  const pid = await w.acceptedPosition(3, 100);
  const works = await worksSchedule(w);
  const mat = await w.issuedPayment([pid]);
  return { works, mat };
}

describe('FOR-11-06 UI: combined payments tab (staff)', { tags: ['for-11-06', 'ui', 'payments'] }, () => {
  test('TC-B1-01 MANAGER sees works tranches and the materials payment in one table with kind chips and columns', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      await paymentsFixture(w);
      const rows = await apiRows(w, 'MANAGER');
      expect(rows.length).toBeGreaterThanOrEqual(2);
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'clientPayments');
      await expect(screen.getByTestId('client-payments-tab')).toBeVisible();
      await expect(screen.getByRole('heading', 'Płatności')).toBeVisible();
      for (const r of rows) await expect(screen.getByTestId(`client-payment-row-${r.id}`)).toBeVisible();
      const kinds = new Set(rows.map((r) => r.kind));
      for (const kind of kinds) {
        await expect(screen.getByTestId('client-payments-table').getByText(kind === 'WORKS' ? 'Prace' : 'Materiały').first()).toBeVisible();
      }
      for (const col of ['Numer', 'Termin płatności', 'Kwota brutto', 'Zapłacono', 'Pozostało', 'Status', 'Rachunek']) {
        await expect(screen.getByRole('columnheader', col)).toBeVisible();
      }
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-02 the kind filter narrows the rows and the totals follow it', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      await paymentsFixture(w);
      const all = await apiRows(w, 'MANAGER');
      const materials = await apiRows(w, 'MANAGER', 'kind=MATERIALS');
      const works = await apiRows(w, 'MANAGER', 'kind=WORKS');
      expect(materials.length + works.length).toBe(all.length);
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'clientPayments');
      await expect(screen.getByTestId('totals-count')).toContainText(String(all.length));
      await screen.getByTestId('filter-kind').tap();
      await screen.getByRole('option', 'Materiały').tap();
      await expect(screen.getByTestId('totals-count')).toContainText(String(materials.length));
      for (const r of works) await expect(screen.getByTestId(`client-payment-row-${r.id}`)).toBeHidden();
      for (const r of materials) await expect(screen.getByTestId(`client-payment-row-${r.id}`)).toBeVisible();
      await screen.getByTestId('filter-kind').tap();
      await screen.getByRole('option', 'Wszystkie rodzaje').tap();
      await expect(screen.getByTestId('totals-count')).toContainText(String(all.length));
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-02 step 2 the "overdue only" checkbox shows exactly the overdue rows of the API', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { works } = await paymentsFixture(w);
      test.skip(!works.published, 'the works schedule could not be published in this stack (no estimate basis): no overdue tranche');
      const overdue = await apiRows(w, 'MANAGER', 'overdue=true');
      const all = await apiRows(w, 'MANAGER');
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'clientPayments');
      await screen.getByTestId('filter-overdue').check();
      for (const r of overdue) await expect(screen.getByTestId(`client-payment-row-${r.id}`)).toBeVisible();
      for (const r of all.filter((x) => !overdue.some((o) => o.id === x.id))) {
        await expect(screen.getByTestId(`client-payment-row-${r.id}`)).toBeHidden();
      }
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-03 clicking a row opens the card with the lines, the "pay to" block and the PDF button', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { mat } = await paymentsFixture(w);
      test.skip(mat.mode !== 'api', 'the PDF storage is down: the materials payment stays a draft (no PDF, no lines snapshot)');
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'clientPayments');
      await screen.getByTestId(`client-payment-row-${mat.id}`).tap();
      const drawer = screen.getByTestId('client-payment-drawer');
      await expect(drawer).toBeVisible();
      await expect(drawer.getByTestId('client-payment-lines')).toBeVisible();
      await expect(drawer.getByRole('button', 'Pobierz PDF')).toBeVisible();
      await expect(drawer.getByTestId('pay-to-block').or(drawer.getByTestId('pay-to-missing'))).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-04 the "Proformy" tab is gone and the legacy URL lands on "Płatności" with its query', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'clientPayments');
      await expect(screen.getByTestId('workspace-tab-clientProformas')).toBeHidden();
      await expect(screen.getByTestId('workspace-tab-clientPayments')).toBeVisible();
      await app.open(`/projects/${w.projectId}/clientProformas?proforma=1`);
      await expect(browser).toHaveURL(new RegExp(`/projects/${w.projectId}/clientPayments\\?proforma=1`));
      await expect(screen.getByTestId('client-payments-tab')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-05 an effective account decides between the "Pay to" block and the missing-account warning', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { works } = await paymentsFixture(w);
      const rows = await apiRows(w, 'MANAGER', 'kind=WORKS');
      test.skip(rows.length === 0 || !works.scheduleId, 'no works tranche in this stack');
      const row = rows[0];
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'clientPayments');
      // Without a project account the effective account is the company profile one (or none).
      const expectMissing = !row.effectiveBankAccount;
      await screen.getByTestId(`client-payment-row-${row.id}`).tap();
      await expect(screen.getByTestId(expectMissing ? 'pay-to-missing' : 'pay-to-block')).toBeVisible();
      if (expectMissing) await expect(screen.getByTestId('pay-to-missing')).toContainText('rachunku bankowego');
      // Setting the project account (API) switches the card to the project IBAN after a reload.
      expect((await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_PL })).status).toBe(200);
      await app.open(`/projects/${w.projectId}/clientPayments`);
      await screen.getByTestId(`client-payment-row-${row.id}`).tap();
      await expect(screen.getByTestId('pay-to-account')).toContainText('PL61 1090 1014 0000 0712 1981 2874');
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-06 UI: payments tab (client)', { tags: ['for-11-06', 'ui', 'payments'] }, () => {
  test('TC-B2-01 CLIENT sees "Płatności" cards of issued documents, no "Proformy" tab', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { mat } = await paymentsFixture(w);
      test.skip(mat.mode !== 'api', 'the PDF storage is down: no issued materials payment for the client');
      const cards = rowsOf(await get(`/api/client-payments?projectId=${w.projectId}&size=100`, w.t('CLIENT')));
      expect(cards.some((c) => c.id === mat.id)).toBe(true);
      await loginAndOpenTab(app, screen, browser, w, 'CLIENT', 'clientPayments');
      await expect(screen.getByTestId('client-payment-cards')).toBeVisible();
      await expect(screen.getByTestId('workspace-tab-clientProformas')).toBeHidden();
      await expect(screen.getByTestId('client-payment-card')).toHaveCount(cards.length);
      await expect(screen.getByTestId('client-payment-kind').first()).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-02 the card shows the payee account and the transfer title with copy buttons; materials add PDF', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { mat } = await paymentsFixture(w);
      test.skip(mat.mode !== 'api', 'the PDF storage is down: no issued materials payment for the client');
      expect((await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_PL })).status).toBe(200);
      await loginAndOpenTab(app, screen, browser, w, 'CLIENT', 'clientPayments', `?payment=${mat.id}`);
      const card = screen.getByTestId('client-payment-card').filter({ hasText: 'Materiały' }).first();
      await expect(card).toBeVisible();
      await expect(card.getByTestId('client-payment-account')).toContainText('PL61 1090 1014 0000 0712 1981 2874');
      await expect(card.getByRole('button', 'Kopiuj numer rachunku')).toBeVisible();
      await expect(card.getByTestId('client-payment-title-block')).toBeVisible();
      await expect(card.getByRole('button', 'Kopiuj tytuł przelewu')).toBeVisible();
      await expect(card.getByRole('button', 'Pobierz PDF')).toBeVisible();
      await expect(card.getByTestId('client-payment-remaining')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-03 the client page and its API answers carry no purchase prices, seller names or position ids; a foreign project is refused', async ({ app, screen, browser }) => {
    const w = await makeWorld({ tags: ['CLIENT2'] });
    try {
      const { mat } = await paymentsFixture(w);
      test.skip(mat.mode !== 'api', 'the PDF storage is down: no issued materials payment for the client');
      const api = await get(`/api/client-payments?projectId=${w.projectId}&size=100`, w.t('CLIENT'));
      expect(api.status).toBe(200);
      expect(api.text).not.toMatch(/purchase|seller|positionId/i);
      await loginAndOpenTab(app, screen, browser, w, 'CLIENT', 'clientPayments');
      await screen.getByTestId('client-payment-toggle-lines').first().tap();
      const text = await pageText(browser);
      expect(text).not.toContain(`QA06-${w.id}-SELLER`);
      expect(text).not.toMatch(/clientPayments\./);
      // Membership scope (ClientPaymentSpecifications.projectScope): a foreign project yields an empty page, not its rows.
      const foreign = await get(`/api/client-payments?projectId=${w.otherProjectId}`, w.t('CLIENT'));
      expect(foreign.status).toBe(200);
      expect(JSON.parse(foreign.text).totalElements).toBe(0);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-04 ?payment={id} highlights the card of that payment', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { mat } = await paymentsFixture(w);
      test.skip(mat.mode !== 'api', 'the PDF storage is down: no issued materials payment for the client');
      await loginAndOpenTab(app, screen, browser, w, 'CLIENT', 'clientPayments', `?payment=${mat.id}`);
      await expect(screen.getByTestId('client-payment-card').first()).toBeVisible();
      const classes: string = await browser.evaluate(
        (id: number) => document.querySelector(`[data-testid="client-payment-card"][data-payment-id="${id}"]`)?.className ?? '',
        mat.id,
      );
      expect(classes).toContain('ring-2');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-01 step 3 a worker-free project member list: FOREMAN sees the staff table, not client cards', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      await paymentsFixture(w);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open(`/projects/${w.projectId}/clientPayments`);
      // FOREMAN has no CLIENT_PAYMENTS access in the matrix: either the table or the forbidden page, never client cards.
      await expect(screen.getByTestId('client-payment-cards')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });
});
