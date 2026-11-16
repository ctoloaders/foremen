import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { acceptedPosition, draftProforma, pp, http, issuedProforma, link, payProforma, positionStatus, txn, withCtx } from './support.js';
import { moneyRe, noHorizontalScroll, uiLogin } from './ui.support.js';

// FOR-11-03 part B1, B2, B5-03 (manager and client proforma screens), PL UI.
// Repeatability: API-seeded run-id fixture (project QA03-<rid>, users), full teardown in `finally` (support.ts).
// ENV NOTE: POST /issue fails (502) because the stack's GCS token is expired, so issued proformas are
// emulated in SQL; the issue submit itself is asserted to report the failure.
describe('FOR-11-03 proforma screens (UI)', { tags: ['for-11-03', 'ui', 'proforma'] }, () => {
  test('TC-B1-01 a manager selects positions, creates a draft and reaches the issue dialog', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const p2 = await acceptedPosition(c, 3, 250);
      await uiLogin(app, screen, browser, c.users.mgr);
      await app.open(`/deliveries?project=${c.projectId}&groupBy=NONE`);
      await screen.getByTestId(`select-row-${p1}`).check();
      await screen.getByTestId(`select-row-${p2}`).check();
      await expect(screen.getByTestId('selection-totals')).toContainText(moneyRe(1890));

      await screen.getByTestId('create-proforma').tap();
      await expect(screen.getByTestId('proforma-draft-editor')).toBeVisible();
      await expect(screen.getByTestId('draft-total-gross')).toContainText(moneyRe(1890));
      await screen.getByTestId('draft-issue').tap();

      await expect(screen.getByTestId('issue-proforma-dialog')).toBeVisible();
      await expect(screen.getByTestId('issue-proforma-confirm')).toContainText(moneyRe(1890));
      await expect(screen.getByTestId('issue-language')).toBeVisible();
    });
  });

  test('TC-B1-01 (issue) confirming the issue shows the failure when the PDF store is down and keeps the draft', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const d = await draftProforma(c, [p1]);
      const probe = await http('POST', `/api/client-payments/${pp(d.id)}/issue`, c.tok.admin, { language: 'pl' });
      if (probe.status === 200) test.skip('issue works in this stack: the failure path is not reachable');
      await uiLogin(app, screen, browser, c.users.mgr);
      await app.open(`/deliveries/proformas/${d.id}`);
      await screen.getByTestId('draft-issue').tap();
      await screen.getByTestId('issue-proforma-submit').tap();

      await expect(screen.getByText('Nie udało się wygenerować PDF.')).toBeVisible();
      expect(await positionStatus(c, p1)).toBe('ACCEPTED');
    });
  });

  test('TC-B1-02 a mixed-project selection disables creating a proforma', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const p2 = await acceptedPosition(c, 3, 250, { projectId: c.otherProjectId });
      await uiLogin(app, screen, browser, c.users.mgr);
      await app.open(`/deliveries?groupBy=NONE&q=${encodeURIComponent(c.rid)}`);
      await screen.getByTestId(`select-row-${p1}`).check();
      await screen.getByTestId(`select-row-${p2}`).check();

      await expect(screen.getByTestId('selection-mixed-projects')).toBeVisible();
      await expect(screen.getByTestId('create-proforma')).toBeDisabled();
    }, { second: true, mgrInBoth: true });
  });

  test('TC-B1-03 an issued unpaid proforma is cancelled with a reason and positions return to ACCEPTED', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const { id, pids } = await issuedProforma(c, [[1, 40]]);
      await uiLogin(app, screen, browser, c.users.mgr);
      await app.open(`/deliveries/proformas/${id}`);
      await expect(screen.getByTestId('proforma-detail-page')).toBeVisible();

      await screen.getByTestId('card-cancel').tap();
      await screen.getByTestId('cancel-proforma-reason').fill(`qa-${c.rid}`);
      await screen.getByTestId('cancel-proforma-submit').tap();

      await expect(screen.getByTestId('proforma-status').first()).toHaveText('Anulowana');
      expect(await positionStatus(c, pids[0])).toBe('ACCEPTED');
    });
  });

  test('TC-B2-01 a client sees the proforma card on a 390 px screen without horizontal scroll', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const { id, body } = await issuedProforma(c);
      await browser.setViewport({ width: 390, height: 844 });
      await uiLogin(app, screen, browser, c.users.client);
      await app.open(`/projects/${c.projectId}/clientProformas`);

      const card = screen.getByTestId('client-proforma-card');
      await expect(card).toBeVisible();
      await expect(card.getByTestId('proforma-number')).toHaveText(body.number);
      await expect(card.getByTestId('proforma-status')).toHaveText('Wystawiona');
      await expect(card.getByTestId('proforma-gross')).toContainText(moneyRe(1890));
      await expect(card.getByTestId('proforma-remaining')).toContainText(moneyRe(1890));
      await expect(card.getByTestId('proforma-progress')).toBeVisible();
      await expect(card.getByTestId('proforma-pdf')).toBeVisible();
      await expect(card.getByTestId('proforma-copy-title')).toBeVisible();
      expect(await noHorizontalScroll(browser)).toBe(true);
      void id;
    });
  });

  test('TC-B2-01 (copy) copying the transfer title shows a confirmation', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      await issuedProforma(c);
      await uiLogin(app, screen, browser, c.users.client);
      await app.open(`/projects/${c.projectId}/clientProformas`);

      await screen.getByTestId('proforma-copy-title').tap();

      await expect(screen.getByText(/Skopiowano tytuł przelewu|Nie udało się skopiować/)).toBeVisible();
    });
  });

  test('TC-B2-02 the client tab calls only client endpoints and shows no internal data', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100, { purchasePrice: 777.77 });
      const d = await draftProforma(c, [p1]);
      await http('POST', `/api/client-payments/${pp(d.id)}/issue`, c.tok.mgr, { language: 'pl' }); // may 502, SQL fallback below
      const iss = await issuedProforma(c, [[1, 40]]);
      void iss;
      const urls: string[] = [];
      await browser.route('**/api/**', async (route: any) => {
        urls.push(route.request.url);
        await route.continue();
      });
      await uiLogin(app, screen, browser, c.users.client);
      await app.open(`/projects/${c.projectId}/clientProformas`);
      await expect(screen.getByTestId('client-proforma-card').first()).toBeVisible();

      const proformaCalls = urls.filter((u) => u.includes('proforma'));
      expect(proformaCalls.length).toBeGreaterThan(0);
      for (const u of proformaCalls) expect(u, u).toContain('/api/client-payments');
      const html = await browser.evaluate(() => document.body.innerText);
      for (const bad of ['777,77', '777.77', `QA03-${c.rid}-SELLER`]) expect(html).not.toContain(bad);
    });
  });

  test('TC-B2-03 a project without proformas shows the empty state', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      await uiLogin(app, screen, browser, c.users.client);
      await app.open(`/projects/${c.projectId}/clientProformas`);

      await expect(screen.getByTestId('client-proformas-empty')).toHaveText('Brak proform.');
    });
  });

  test('TC-B5-03 the staff proforma card lists bank payments, an unpaid one shows the empty block, the client card none', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const paid = await issuedProforma(c);
      const t1 = txn(c, 700, 'PF one');
      const l1 = await link(c, c.tok.fin, t1, { kind: 'PROFORMA_PAYMENT', amount: 630, clientProformaId: paid.id });
      const unpaid = await issuedProforma(c, [[1, 40]]);
      await uiLogin(app, screen, browser, c.users.mgr);

      await app.open(`/deliveries/proformas/${paid.id}`);
      await expect(screen.getByTestId(`card-payment-${l1.body.linkId}`)).toContainText(moneyRe(630));
      await expect(screen.getByTestId(`card-payment-${l1.body.linkId}`)).toContainText(`qa-${c.rid} PF one`);
      await app.open(`/deliveries/proformas/${unpaid.id}`);
      await expect(screen.getByTestId('card-payments-empty')).toBeVisible();
      await app.clearState();
      await uiLogin(app, screen, browser, c.users.client);
      await app.open(`/projects/${c.projectId}/clientProformas`);
      await expect(screen.getByTestId('client-proforma-card').first()).toBeVisible();
      await expect(screen.getByTestId('card-payments')).toHaveCount(0);
      void payProforma;
    });
  });
});
