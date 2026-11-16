import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  acceptedPosition, errMatches, feedTxn, getEo, getPf, http, issuedProforma, link, ok, payProforma, positionStatus, txn, withCtx, type Ctx,
} from './support.js';
import { moneyRe, uiLogin } from './ui.support.js';
import { createUser } from '../../support/users.js';

// FOR-11-03 part B3, B4, B5, B6, B7 (external orders, financier queue, bell, ledger dialog, routes). PL UI.
// Repeatability: API-seeded run-id fixture per test, full teardown in `finally` (support.ts).
describe('FOR-11-03 external order screens (UI)', { tags: ['for-11-03', 'ui', 'orders'] }, () => {
  const line = (positionId: number, quantity: number, unitPurchaseNet: number) => ({ positionId, quantity, unitPurchaseNet, aboveCeilingAcknowledged: false });
  /** Paid proforma, released positions, a draft order Eo1 (P1 x6 @80, P2 x3 @200). */
  const eo1 = async (c: Ctx) => {
    const r = await issuedProforma(c);
    await payProforma(c, r.id, 1890);
    const e = await http('POST', '/api/external-orders', c.tok.buyer, {
      projectId: c.projectId, sellerId: c.sellerId,
      header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 50, deliveryVatRateId: c.vat23 },
      lines: [line(r.pids[0], 6, 80), line(r.pids[1], 3, 200)],
    });
    expect(e.status, e.text).toBe(200);
    return { p1: r.pids[0], p2: r.pids[1], proformaId: r.id, id: e.body.id as number };
  };
  const bell = async (screen: any) => {
    await screen.getByTestId('notification-bell').tap();
    return screen.getByTestId('notification-list');
  };

  test('TC-B3-01 a buyer creates an order, the ceiling needs an acknowledgement, then sends it', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const r = await issuedProforma(c);
      await payProforma(c, r.id, 1890);
      const [p1, p2] = r.pids;
      await uiLogin(app, screen, browser, c.users.buyer);
      await app.open(`/deliveries/external-orders/new?project=${c.projectId}`);
      await expect(screen.getByTestId('external-order-editor')).toBeVisible();

      const seller = screen.getByTestId('external-order-header').getByRole('combobox', 'Sprzedawca');
      await seller.tap();
      await screen.getByRole('textbox', 'Szukaj po nazwie lub NIP').fill(c.sellerName);
      await screen.getByRole('option').filter({ hasText: c.sellerName }).tap();
      await screen.getByTestId('external-order-reference').fill(`QA-${c.rid}-ORD`);
      await screen.getByTestId('external-order-position-picker').selectOption({ value: String(p1) });
      await screen.getByTestId('external-order-add-line').tap();
      await screen.getByTestId('external-order-position-picker').selectOption({ value: String(p2) });
      await screen.getByTestId('external-order-add-line').tap();
      await screen.getByTestId(`external-order-line-qty-${p1}`).fill('6');
      await screen.getByTestId(`external-order-line-qty-${p2}`).fill('3');
      await screen.getByTestId(`external-order-line-price-${p2}`).fill('200');
      await expect(screen.getByTestId(`external-order-line-remaining-${p1}`)).toContainText('10');
      await screen.getByTestId(`external-order-line-price-${p1}`).fill('120');

      await expect(screen.getByTestId(`external-order-line-ceiling-${p1}`)).toBeVisible();
      await expect(screen.getByTestId('external-order-save')).toBeDisabled();
      await screen.getByTestId(`external-order-line-ack-${p1}`).check();
      await expect(screen.getByTestId('external-order-save')).toBeEnabled();
      await screen.getByTestId(`external-order-line-ack-${p1}`).uncheck();
      await screen.getByTestId(`external-order-line-price-${p1}`).fill('80');
      await expect(screen.getByTestId('external-order-save')).toBeEnabled();
      await screen.getByTestId('external-order-save').tap();

      await expect(browser).toHaveURL(/\/deliveries\/external-orders\/\d+$/);
      await screen.getByTestId('external-order-send').tap();
      await screen.getByTestId('external-order-send-confirm').tap();
      await expect(screen.getByTestId('external-order-status')).toHaveText('Wysłane do płatności');
      await expect(screen.getByTestId('external-order-reference')).toBeDisabled();
      expect(await positionStatus(c, p2)).toBe('ORDERED');
    });
  });

  test('TC-B3-02 a returned order shows the reason and is editable again', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      await http('POST', `/api/external-orders/${e.id}/return`, c.tok.fin, { reason: `qa-${c.rid}` });
      await uiLogin(app, screen, browser, c.users.buyer);

      await app.open(`/deliveries/external-orders/${e.id}`);

      await expect(screen.getByTestId('external-order-return-reason')).toContainText(`qa-${c.rid}`);
      await expect(screen.getByTestId('external-order-reference')).toBeEnabled();
    });
  });

  test('TC-B4-01 the financier returns an order with a reason, then marks the resent order paid', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      await uiLogin(app, screen, browser, c.users.fin);
      await app.open('/deliveries/external-orders');
      const card = screen.getByTestId('financier-order-card').filter({ hasText: `QA-${c.rid}-ORD` });
      await expect(card).toBeVisible();

      await card.getByTestId('financier-return').tap();
      await expect(screen.getByTestId('return-order-submit')).toBeDisabled();
      await screen.getByTestId('return-order-reason').fill(`qa-${c.rid}`);
      await screen.getByTestId('return-order-submit').tap();

      await expect(card).toBeHidden();
      expect((await getEo(c, e.id)).status).toBe('DRAFT');

      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      await app.open('/deliveries/external-orders');
      await card.getByTestId('financier-mark-paid').tap();
      await expect(screen.getByTestId('mark-paid-dialog')).toBeVisible();
      await screen.getByTestId('mark-paid-submit').tap();

      await app.open(`/deliveries/external-orders/${e.id}`);
      await expect(screen.getByTestId('external-order-paid-source')).toContainText('Finansista');
      expect(await positionStatus(c, e.p2)).toBe('SUPPLIER_PAID');
    });
  });

  test('TC-B4-02 the bell shows role-specific notifications and the order link highlights the order', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      await http('POST', `/api/external-orders/${e.id}/mark-paid`, c.tok.fin, {});
      await uiLogin(app, screen, browser, c.users.fin);
      await expect(await bell(screen)).toContainText('Zamówienie do opłacenia');
      await app.clearState();
      await uiLogin(app, screen, browser, c.users.mgr);
      await expect(await bell(screen)).toContainText('Proforma opłacona');
      await app.clearState();

      await uiLogin(app, screen, browser, c.users.buyer);
      const list = await bell(screen);
      await expect(list).toContainText('Zamówienie opłacone, uzupełnij dostawę');
      await list.getByText('Zamówienie opłacone, uzupełnij dostawę').tap();

      await expect(browser).toHaveURL(new RegExp(`/deliveries/external-orders\\?order=${e.id}`));
      await expect(screen.getByTestId(`external-order-row-${e.id}`)).toHaveAttribute('data-highlighted', 'true');
    });
  });

  // BUG-3 (FINDINGS.md): the manager's proforma-paid deep link opens the project tab with only a list link, no proforma shown/highlighted
  test('TC-B4-02 (b) the proforma-paid notification of a manager leads to the proforma', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const r = await issuedProforma(c);
      await payProforma(c, r.id, 1890);
      await uiLogin(app, screen, browser, c.users.mgr);
      const list = await bell(screen);
      await list.getByText('Proforma opłacona').tap();

      await expect(browser).toHaveURL(new RegExp(`proforma=${r.id}`));
      // the target must show that proforma (row or card), not only a generic link
      await expect(screen.getByTestId(`proforma-row-${r.id}`).or(screen.getByTestId('proforma-card')).first()).toBeVisible();
    });
  });

  const openLedger = async (app: any, screen: any, browser: any, c: Ctx, txnId: number) => {
    await uiLogin(app, screen, browser, c.users.fin);
    await app.open(`/company/bank-transactions?transactionId=${txnId}`);
    await expect(screen.getByTestId('ledger-drawer-body')).toBeVisible();
  };
  const pickKind = async (screen: any, label: string) => {
    await screen.getByTestId('ledger-link-kind').tap();
    await screen.getByRole('option', label).tap();
  };

  test('TC-B5-01 a financier links a payment to a proforma from the ledger dialog', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const r = await issuedProforma(c);
      const t = txn(c, 1890, 'PF full');
      const bodies: string[] = [];
      await browser.route('**/api/bank/transactions/*/links', async (route: any) => {
        if (route.request.method === 'POST') bodies.push(route.request.postData ?? '');
        await route.continue();
      });
      await openLedger(app, screen, browser, c, t);

      await screen.getByRole('button', 'Powiąż ręcznie').tap();
      await expect(screen.getByTestId('ledger-link-editor')).toBeVisible();
      await pickKind(screen, 'Płatność za proformę');
      await screen.getByTestId('ledger-link-search').fill(r.body.number);
      await screen.getByTestId('ledger-link-target').first().tap();
      await expect(screen.getByTestId('ledger-link-amount')).toHaveValue(/1890|1 890/);
      await screen.getByTestId('ledger-link-save').tap();

      await expect(screen.getByTestId('ledger-drawer-link')).toBeVisible();
      expect((await getPf(c, r.id)).status).toBe('PAID');
      expect(bodies.join('|')).toContain(`"clientProformaId":${r.id}`);
    });
  });

  test('TC-B5-02 a financier confirms a proforma suggestion and sees the reasons', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const r = await issuedProforma(c);
      const t = feedTxn(c, 777, `${r.body.number} zaplata`);
      await http('POST', '/api/bank/transactions/detect', c.tok.fin, { ids: [t] });
      await openLedger(app, screen, browser, c, t);

      await expect(screen.getByTestId('ledger-suggestion-rationale')).toContainText('Numer proformy w tytule');
      await screen.getByTestId('ledger-suggestion-confirm').tap();

      await expect(screen.getByTestId('ledger-drawer-link')).toBeVisible();
      expect((await getPf(c, r.id)).paidGross).toBe(777);
    });
  });

  test('TC-B5-04 an order card links to its proformas', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await uiLogin(app, screen, browser, c.users.buyer);
      await app.open(`/deliveries/external-orders/${e.id}`);

      const ref = screen.getByTestId(`external-order-proforma-${e.proformaId}`);
      await expect(ref).toBeVisible();
      await expect(ref).toContainText(/PF\/\d{4}\/\d{6}/);
      await ref.getByRole('link').tap();

      await expect(browser).toHaveURL(new RegExp(`/deliveries/proformas/${e.proformaId}$`));
    });
  });

  test('TC-B5-04 (b) an order without a proforma shows no proforma block', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const pkg = (await ok('POST', '/api/purchase-positions', c.tok.admin, {
        projectId: c.projectId, kind: 'FINISHING', sourcingPath: 'PACKAGE', plannedMaterialId: c.materialId, quantity: 2, salePrice: 10, saleVatRateId: c.vat8,
      })).id as number;
      await ok('POST', `/api/purchase-positions/${pkg}/transition`, c.tok.admin, { target: 'WITH_BUYER' });
      const e = await http('POST', '/api/external-orders', c.tok.buyer, { projectId: c.projectId, sellerId: c.sellerId, header: { referenceNumber: `QA-${c.rid}-ORD` }, lines: [line(pkg, 2, 5)] });
      expect(e.status, e.text).toBe(200);
      await uiLogin(app, screen, browser, c.users.buyer);
      await app.open(`/deliveries/external-orders/${e.body.id}`);

      await expect(screen.getByTestId('external-order-editor')).toBeVisible();
      await expect(screen.getByTestId('external-order-proformas')).toHaveCount(0);
    });
  });

  test('TC-B5-05 planner groups "Bez proformy" / "Bez zamówienia" request the server filter', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const r = await issuedProforma(c);
      await payProforma(c, r.id, 1890);
      const p3 = await acceptedPosition(c, 1, 40);
      const urls: string[] = [];
      await browser.route('**/api/purchase-positions/planner*', async (route: any) => {
        urls.push(route.request.url);
        await route.continue();
      });
      await uiLogin(app, screen, browser, c.users.mgr);

      await app.open(`/deliveries?project=${c.projectId}&groupBy=PROFORMA`);
      await screen.getByTestId('group-toggle-NO_PROFORMA').first().tap();

      await expect(screen.getByTestId(`planner-row-${p3}`)).toBeVisible();
      expect(urls.some((u) => u.includes('noProforma=true'))).toBe(true);
      await expect(screen.getByTestId(`planner-row-${r.pids[0]}`)).toHaveCount(0);
    });
  });

  test('TC-B5-06 the ledger dialog sends clientProformaId / externalOrderId', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      const sent = await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      const t = txn(c, -sent.body.payableGross, 'EO');
      const bodies: string[] = [];
      await browser.route('**/api/bank/transactions/*/links', async (route: any) => {
        if (route.request.method === 'POST') bodies.push(route.request.postData ?? '');
        await route.continue();
      });
      await openLedger(app, screen, browser, c, t);

      await screen.getByRole('button', 'Powiąż ręcznie').tap();
      await pickKind(screen, 'Płatność za zamówienie zewnętrzne');
      await screen.getByTestId('ledger-link-search').fill(`QA-${c.rid}-ORD`);
      await screen.getByTestId('ledger-link-target').first().tap();
      await screen.getByTestId('ledger-link-save').tap();

      await expect(screen.getByTestId('ledger-drawer-link')).toBeVisible();
      expect(bodies.join('|')).toContain(`"externalOrderId":${e.id}`);
      expect((await getEo(c, e.id)).paidSource).toBe('LEDGER');
    });
  });

  test('TC-B6-01 the proforma, order and queue screens have no raw i18n keys in PL and RU', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      const leaked = async () => ((await browser.evaluate(() => document.body.innerText)) as string).match(/\b(clientProformas|externalOrders|purchasePlanner|bankTransactions)\.[a-zA-Z.]+/g) ?? [];
      const seen: string[] = [];
      // R22.2: the FINANCIER sees only external orders; the proforma screens are checked as the MANAGER.
      const visits = [
        { user: c.users.fin, paths: ['/deliveries/external-orders', `/deliveries/external-orders/${e.id}`] },
        { user: c.users.mgr, paths: ['/deliveries/proformas', `/deliveries/proformas/${e.proformaId}`] },
      ];
      for (const { user, paths } of visits) {
        await app.clearState();
        await uiLogin(app, screen, browser, user);
        for (const lang of ['pl', 'ru']) {
          await browser.evaluate((l: string) => localStorage.setItem('i18nextLng', l), lang);
          for (const path of paths) {
            await app.open(path);
            await expect(screen.getByTestId('financier-queue').or(screen.getByTestId('proforma-list-page')).or(screen.getByTestId('proforma-detail-page')).or(screen.getByTestId('external-order-editor')).first()).toBeVisible();
            seen.push(...(await leaked()));
          }
        }
      }

      expect(seen).toEqual([]);
    });
  });

  test('TC-B7-01 the BUYER and MANAGER open proformas, orders and the planner', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      for (const u of [c.users.mgr, c.users.buyer]) {
        await app.clearState();
        await uiLogin(app, screen, browser, u);
        await app.open('/deliveries/proformas');
        await expect(screen.getByTestId('proforma-list')).toBeVisible();
        await app.open('/deliveries/external-orders');
        await expect(screen.getByTestId('external-order-list')).toBeVisible();
        await app.open('/deliveries');
        await expect(screen.getByTestId('purchase-planner-page')).toBeVisible();
      }
    });
  });

  test('TC-B7-01 (fin) the FINANCIER sees only the order queue, not the planner', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      await uiLogin(app, screen, browser, c.users.fin);

      await app.open('/deliveries');
      await expect(browser).toHaveURL(/\/403/);
      await app.open('/deliveries/external-orders');
      await expect(screen.getByTestId('financier-queue')).toBeVisible();
    });
  });

  // BUG-1 (FINDINGS.md): the CLIENT reaches the staff screen /deliveries/proformas (error state) instead of the 403 page
  test('TC-B7-01 (client) a CLIENT cannot open the staff proforma list', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      await uiLogin(app, screen, browser, c.users.client);

      await app.open('/deliveries/proformas');

      await expect(browser).toHaveURL(/\/403/);
      await app.open('/deliveries/external-orders');
      await expect(browser).toHaveURL(/\/403/);
    });
  });

  test('TC-B7-01 (foreman) a FOREMAN cannot open proformas and orders', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      const fm = createUser('FOREMAN', 'p03fm');
      c.extraUsers.push(fm);
      await uiLogin(app, screen, browser, fm);

      await app.open('/deliveries/proformas');
      await expect(browser).toHaveURL(/\/403/);
      await app.open('/deliveries/external-orders');
      await expect(browser).toHaveURL(/\/403/);
    });
  });

  // BUG-2 (FINDINGS.md): R22.2 says the FINANCIER sees only external orders, but the menu shows "Proformy" and the route opens
  test('TC-B7-02 the FINANCIER menu has only the orders entry under "Dostawy"', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      await uiLogin(app, screen, browser, c.users.fin);
      await app.open('/deliveries/external-orders');
      const nav = screen.getByRole('navigation');

      await expect(nav.getByRole('link', 'Zamówienia zewnętrzne')).toBeVisible();
      await expect(nav.getByRole('link', 'Proformy')).toHaveCount(0);
    });
  });

  test('TC-B7-02 the menu entry "Dostawy" of the FINANCIER leads to the order queue', async ({ app, screen, browser }) => {
    await withCtx(async (c) => {
      await uiLogin(app, screen, browser, c.users.fin);

      await screen.getByRole('navigation').getByRole('link', 'Dostawy').tap();

      await expect(browser).toHaveURL(/\/deliveries\/external-orders/);
      void errMatches; void feedTxn; void link; void moneyRe;
    });
  });
});
