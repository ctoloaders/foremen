import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { day, ev, get, makeWorld, openReceipt, post, psql, receive, uiLogin, voidReceipt, type World } from './support.js';

// FOR-11-05 Part B (UI): B1 screen/tabs/rights, B2 receive sheet, B3 accept all, B4 history and void.
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-05-goods-receipt/test-cases.md.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()) in `finally`. Language PL (config).

const sheet = (screen: any) => screen.getByTestId('receive-sheet');
const setEta = (id: number, d: string) => psql(`UPDATE shipments SET eta_date = :'d'::date WHERE id = :sid;`, { d, sid: String(id) });
const accepted = (screen: any, pid: number) => screen.getByTestId(`receipt-card-${pid}`).getByTestId('ledger-accepted');

describe('FOR-11-05 UI: screen, tabs and rights', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B1-01 foreman at 360 px: title, tabs, card with ledger bar, shipment chip and receive button; no money; RECEIVED and foreign positions absent', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pa = w.position({ quantity: 3 });
      const sa = await w.shipment([[pa, 3]]);
      const pdone = w.position({ quantity: 1 });
      const sdone = await w.shipment([[pdone, 1]]);
      await receive(w.t('FOREMAN'), [ev(pdone, 'ACCEPTED', 1, { shipmentId: sdone })]);
      const px = w.position({ quantity: 2, projectId: w.otherProjectId });
      await w.shipment([[px, 2]], { projectId: w.otherProjectId });
      await browser.setViewport({ width: 360, height: 800 });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByRole('heading', 'Odbiór dostaw')).toBeVisible();
      await expect(screen.getByRole('tab', 'Do odbioru')).toBeVisible();
      await expect(screen.getByRole('tab', 'Historia')).toBeVisible();
      const card = screen.getByTestId(`receipt-card-${pa}`);
      await expect(card).toBeVisible();
      await expect(card.getByTestId('ledger-bar')).toBeVisible();
      await expect(card.getByRole('img')).toHaveAccessibleName(/Przyjęto 0 z 3/);
      await expect(card).toContainText('DPD');
      await expect(card).toContainText('Brak ETA');
      await expect(screen.getByTestId(`receive-btn-${pa}`)).toBeVisible();
      await expect(screen.getByTestId(`accept-all-${sa}`)).toBeVisible();
      expect(await card.textContent()).not.toMatch(/zł|PLN|EUR|€|cena|kwota|VAT/i);
      await expect(screen.getByTestId(`receipt-card-${pdone}`)).toBeHidden();
      await expect(screen.getByTestId(`receipt-card-${px}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-01 step 3 cards are ordered: delivered shipments first, then by ETA, no ETA last', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pNoEta = w.position({ quantity: 1 });
      const pLate = w.position({ quantity: 1 });
      const pSoon = w.position({ quantity: 1 });
      const pDelivered = w.position({ quantity: 1 });
      await w.shipment([[pNoEta, 1]]);
      setEta(await w.shipment([[pLate, 1]]), day(9));
      setEta(await w.shipment([[pSoon, 1]]), day(2));
      const sd = await w.shipment([[pDelivered, 1]]);
      psql(`UPDATE shipments SET status = 'DELIVERED' WHERE id = ${sd};`);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByTestId(`receipt-card-${pNoEta}`)).toBeVisible();
      const ids: string[] = await browser.evaluate(() =>
        Array.from(document.querySelectorAll('[data-testid^="receipt-card-"]')).map((e) => e.getAttribute('data-testid') as string));
      expect(ids).toEqual([pDelivered, pSoon, pLate, pNoEta].map((p) => `receipt-card-${p}`));
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-02 BUYER has a read-only screen; MANAGER can receive', async ({ app, screen, browser }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const r = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      expect(r.status).toBe(201);
      await uiLogin(app, screen, w.users.BUYER!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByTestId(`receipt-card-${p}`)).toBeVisible();
      await expect(screen.getByTestId(`receive-btn-${p}`)).toBeHidden();
      await expect(screen.getByTestId(`accept-all-${s}`)).toBeHidden();
      await screen.getByRole('tab', 'Historia').tap();
      await expect(screen.getByTestId(`history-row-${r.body.receipts[0].id}`)).toBeVisible();
      await expect(screen.getByRole('button', 'Anuluj wpis')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-02 MANAGER sees the receive buttons', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      await w.shipment([[p, 2]]);
      await uiLogin(app, screen, w.users.MANAGER!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByTestId(`receive-btn-${p}`)).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-02 FINANCIER and CLIENT: no menu entry and "Brak dostępu" on a direct visit', async ({ app, screen }) => {
    const w = await makeWorld(['FINANCIER']);
    try {
      for (const tag of ['FINANCIER', 'CLIENT'] as const) {
        await uiLogin(app, screen, w.users[tag]!);
        await app.open('/deliveries/receipt');
        await expect(screen.getByText('Brak dostępu').first()).toBeVisible();
        await expect(screen.getByRole('link', 'Odbiór dostaw')).toBeHidden();
        await app.clearState();
      }
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-03 the list follows the working project (MANAGER of two projects)', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const pa = w.position({ quantity: 2 });
      await w.shipment([[pa, 2]]);
      const px = w.position({ quantity: 2, projectId: w.otherProjectId });
      await w.shipment([[px, 2]], { projectId: w.otherProjectId });
      await uiLogin(app, screen, w.users.MANAGER!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByTestId(`receipt-card-${pa}`)).toBeVisible();
      await expect(screen.getByTestId(`receipt-card-${px}`)).toBeHidden();
      await openReceipt(app, screen, browser, w.otherProjectId);
      await expect(screen.getByTestId(`receipt-card-${px}`)).toBeVisible();
      await expect(screen.getByTestId(`receipt-card-${pa}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 UI: receive sheet', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B2-01 partial receipt: default quantity = in flight, save 3 of 4 updates the card, history shows the event', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pc = w.position({ quantity: 4 });
      const s1 = await w.shipment([[pc, 2]]);
      const s2 = await w.shipment([[pc, 2]]);
      setEta(s1, day(1));
      setEta(s2, day(3));
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${pc}`).tap();
      await expect(sheet(screen)).toBeVisible();
      await expect(sheet(screen).getByRole('button', 'Przyjęto')).toHaveAttribute('aria-pressed', 'true');
      await expect(sheet(screen).getByLabel('Ilość')).toHaveValue('4');
      await sheet(screen).getByRole('button', 'Zmniejsz').tap();
      await expect(sheet(screen).getByLabel('Ilość')).toHaveValue('3');
      await sheet(screen).getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen)).toBeHidden();
      const card = screen.getByTestId(`receipt-card-${pc}`);
      await expect(card.getByTestId('ledger-accepted')).toContainText('3');
      await expect(card.getByTestId('ledger-inflight')).toContainText('1');
      await expect(card.getByTestId('ledger-outstanding')).toContainText('1');
      await expect(card).toContainText('Częściowo odebrano');
      await screen.getByRole('tab', 'Historia').tap();
      const rows = (await get(`/api/goods-receipts?projectId=${w.projectId}&positionId=${pc}`, w.t('MANAGER'))).body.content;
      expect(rows.reduce((s: number, r: any) => s + r.quantity, 0)).toBe(3);
      await expect(screen.getByTestId(`history-row-${rows[0].id}`)).toContainText('Przyjęto');
      await expect(screen.getByTestId(`history-row-${rows[0].id}`)).toContainText('QA for1105');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-02 client validation: comment, quantity, bounds, the "from accepted" switch only on returns; nothing is sent', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pf = w.position({ quantity: 5 });
      await w.shipment([[pf, 5]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${pf}`).tap();
      const sh = sheet(screen);
      await expect(sh.getByRole('switch')).toBeHidden(); // kind ACCEPTED: no "from accepted"
      await sh.getByRole('button', 'Brak').tap();
      await sh.getByRole('button', 'Zapisz').tap();
      await expect(sh.getByRole('alert')).toContainText('Komentarz jest wymagany');
      await sh.getByLabel('Ilość').fill('0');
      await expect(sh.getByRole('alert')).toContainText('Ilość musi być większa od zera');
      await sh.getByLabel('Ilość').fill('-1');
      await expect(sh.getByRole('alert')).toContainText('Ilość musi być większa od zera');
      await sh.getByLabel('Ilość').fill('1.0001');
      await expect(sh.getByRole('alert')).toContainText('maksymalnie 3 miejsca');
      await sh.getByLabel('Ilość').fill('6');
      await sh.getByLabel('Komentarz', { exact: false }).fill('x');
      await expect(sh.getByRole('alert')).toContainText('Ilość przekracza ilość w drodze (5)');
      await sh.getByRole('button', 'Zwrot').tap();
      await expect(sh.getByRole('switch')).toBeVisible();
      await sh.getByRole('button', 'Przyjęto').tap();
      await expect(sh.getByRole('switch')).toBeHidden();
      const list = (await get(`/api/goods-receipts?projectId=${w.projectId}&positionId=${pf}`, w.t('MANAGER'))).body.content;
      expect(list).toHaveLength(0);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-02 step 5 a server refusal after a stale screen shows an error and keeps the sheet', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pf = w.position({ quantity: 5 });
      const sf = await w.shipment([[pf, 5]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${pf}`).tap();
      const sh = sheet(screen);
      await sh.getByRole('button', 'Brak').tap();
      await sh.getByLabel('Ilość').fill('5');
      await sh.getByLabel('Komentarz', { exact: false }).fill(`rcpt-${w.id}-ui-race`);
      // another device accepts 3 first: only 2 are left in flight
      expect((await receive(w.t('MANAGER'), [ev(pf, 'ACCEPTED', 3, { shipmentId: sf })])).status).toBe(201);
      await sh.getByRole('button', 'Zapisz').tap();
      await expect(sh.getByRole('alert')).toBeVisible();
      await expect(sheet(screen)).toBeVisible();
      const miss = (await get(`/api/goods-receipts?projectId=${w.projectId}&positionId=${pf}&kind=MISSING`, w.t('MANAGER'))).body.content;
      expect(miss).toHaveLength(0);
    } finally {
      await w.cleanup();
    }
  });

  // BUG-2 (FINDINGS.md): ReceiveSheet.onSave swallows the server error (catch -> generic "Nie udało się zapisać odbioru"),
  // so the 409 exceedsInFlight text is not shown and the stale ledger/card is not reloaded (TC-B2-02 step 5).
  test('TC-B2-02 step 5 BUG-2: a server exceedsInFlight refusal shows the localized reason', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pf = w.position({ quantity: 5 });
      const sf = await w.shipment([[pf, 5]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${pf}`).tap();
      const sh = sheet(screen);
      await sh.getByRole('button', 'Brak').tap();
      await sh.getByLabel('Ilość').fill('5');
      await sh.getByLabel('Komentarz', { exact: false }).fill(`rcpt-${w.id}-ui-race`);
      expect((await receive(w.t('MANAGER'), [ev(pf, 'ACCEPTED', 3, { shipmentId: sf })])).status).toBe(201);
      await sh.getByRole('button', 'Zapisz').tap();
      await expect(sh.getByRole('alert')).toContainText('Ilość przekracza ilość w drodze');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-03 shortage: MISSING with a comment leaves the outstanding quantity and a re-delivery shipment appears', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pb = w.position({ quantity: 3 });
      await w.shipment([[pb, 3]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${pb}`).tap();
      const sh = sheet(screen);
      await sh.getByRole('button', 'Brak').tap();
      await sh.getByLabel('Ilość').fill('1');
      await sh.getByLabel('Komentarz', { exact: false }).fill(`rcpt-${w.id}-ui-1`);
      await sh.getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen)).toBeHidden();
      const card = screen.getByTestId(`receipt-card-${pb}`);
      await expect(card.getByTestId('ledger-outstanding')).toContainText('3');
      await expect(card).toContainText('W dostawie');
      const shipments = (await get(`/api/shipments?projectId=${w.projectId}&size=50`, w.t('BUYER'))).body.content as any[];
      expect(shipments.some((x) => x.deliveryMethod === 'SHOP_DELIVERY' && String(x.note ?? '').includes('Re-delivery after receipt'))).toBe(true);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-04 return from accepted: bound by the accepted quantity, then the card shows less accepted', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 3 });
      const s = await w.shipment([[p, 3]]);
      await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 2, { shipmentId: s })]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${p}`).tap();
      const sh = sheet(screen);
      await sh.getByRole('button', 'Zwrot').tap();
      await sh.getByRole('switch').check();
      await sh.getByLabel('Ilość').fill('3');
      await sh.getByLabel('Komentarz', { exact: false }).fill(`rcpt-${w.id}-ui-ret`);
      await sh.getByRole('button', 'Zapisz').tap(); // errors show after the first Save attempt
      await expect(sh.getByRole('alert')).toContainText('Zwrot przekracza przyjętą ilość (2)');
      await sh.getByLabel('Ilość').fill('1');
      await sh.getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen)).toBeHidden();
      await expect(accepted(screen, p)).toContainText('1');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-05 CLIENT_OWN position: no shipment chips, receive the full quantity, the card leaves the list', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pe = w.position({ path: 'CLIENT_OWN', quantity: 2 });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      const card = screen.getByTestId(`receipt-card-${pe}`);
      await expect(card).toBeVisible();
      await expect(card.getByRole('list', 'Przesyłki')).toBeHidden();
      await screen.getByTestId(`receive-btn-${pe}`).tap();
      // test-cases expects 2 (the cap); the product's default is the in-flight quantity (0 without a shipment -> 1, R10.3)
      await expect(sheet(screen).getByLabel('Ilość')).toHaveValue('1');
      await sheet(screen).getByLabel('Ilość').fill('2');
      await sheet(screen).getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen)).toBeHidden();
      await expect.poll(() => w.positionRow(pe).status).toBe('RECEIVED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-06 a network failure on save shows a retry, and the retry records the event once', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 3 });
      await w.shipment([[p, 3]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${p}`).tap();
      let failed = false;
      await browser.route('**/api/goods-receipts', async (route: any) => {
        if (route.request.method === 'POST' && !failed) {
          failed = true;
          await route.abort();
        } else {
          await route.continue();
        }
      });
      await sheet(screen).getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen).getByRole('alert')).toContainText('Nie udało się zapisać odbioru');
      await sheet(screen).getByRole('button', 'Spróbuj ponownie').tap();
      await expect(sheet(screen)).toBeHidden();
      const rows = (await get(`/api/goods-receipts?projectId=${w.projectId}&positionId=${p}`, w.t('MANAGER'))).body.content;
      expect(rows).toHaveLength(1);
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 UI: accept all', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B3-01 accept a whole shipment: confirm dialog lists lines, cancel writes nothing, confirm records and closes the shipment', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pc = w.position({ quantity: 4 });
      const sc1 = await w.shipment([[pc, 2]]);
      const sc2 = await w.shipment([[pc, 2]]);
      setEta(sc1, day(1));
      setEta(sc2, day(3));
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`accept-all-${sc1}`).tap();
      const dlg = screen.getByRole('alertdialog');
      await expect(dlg).toContainText('Przyjąć wszystko?');
      await expect(dlg).toContainText('2');
      await dlg.getByRole('button', 'Anuluj').tap();
      await expect(dlg).toBeHidden();
      expect((await get(`/api/goods-receipts?projectId=${w.projectId}`, w.t('MANAGER'))).body.content).toHaveLength(0);
      await screen.getByTestId(`accept-all-${sc1}`).tap();
      await screen.getByRole('alertdialog').getByRole('button', 'Przyjmij wszystko').tap();
      await expect(screen.getByRole('alertdialog')).toBeHidden();
      await expect(accepted(screen, pc)).toContainText('2');
      await expect(screen.getByTestId(`accept-all-${sc1}`)).toBeHidden();
      await expect(screen.getByTestId(`accept-all-${sc2}`)).toBeVisible();
      const ship = await get(`/api/shipments/${sc1}`, w.t('BUYER'));
      expect(ship.body.status).toBe('DELIVERED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B3-02 accept all on two positions is one batch; a stale line makes the whole batch fail and nothing changes', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p1 = w.position({ quantity: 2 });
      const p2 = w.position({ quantity: 1 });
      const s = await w.shipment([[p1, 2], [p2, 1]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByTestId(`accept-all-${s}`).first()).toBeVisible();
      // p2 is received on another device while this screen is stale
      expect((await receive(w.t('MANAGER'), [ev(p2, 'ACCEPTED', 1, { shipmentId: s })])).status).toBe(201);
      await screen.getByTestId(`accept-all-${s}`).first().tap();
      const dlg = screen.getByRole('alertdialog');
      await expect(dlg).toBeVisible();
      await dlg.getByRole('button', 'Przyjmij wszystko').tap();
      await expect(dlg.getByRole('alert')).toBeVisible();
      const led = (await get(`/api/purchase-positions/${p1}/ledger`, w.t('MANAGER'))).body;
      expect(led.ledger.acceptedNet).toBe(0);
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 UI: history and void', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B4-01 history lists events newest first with kind, quantity and author; an empty project shows the empty state', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 5 });
      const s = await w.shipment([[p, 5]]);
      const a = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      await new Promise((r) => setTimeout(r, 50));
      const m = await receive(w.t('FOREMAN'), [ev(p, 'MISSING', 1, { shipmentId: s, comment: `rcpt-${w.id}-m` })]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByRole('tab', 'Historia').tap();
      // the history loads asynchronously: wait for both rows before reading their order
      await expect(screen.getByTestId(`history-row-${a.body.receipts[0].id}`)).toBeVisible();
      await expect(screen.getByTestId(`history-row-${m.body.receipts[0].id}`)).toBeVisible();
      const ids: string[] = await browser.evaluate(() =>
        Array.from(document.querySelectorAll('[data-testid^="history-row-"]')).map((e) => e.getAttribute('data-testid') as string));
      expect(ids).toEqual([m.body.receipts[0].id, a.body.receipts[0].id].map((i) => `history-row-${i}`));
      await expect(screen.getByTestId(`history-row-${m.body.receipts[0].id}`)).toContainText('Brak');
      await expect(screen.getByTestId(`history-row-${m.body.receipts[0].id}`)).toContainText(`rcpt-${w.id}-m`);
      await expect(screen.getByTestId('history-empty')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-01 empty history of a project without events', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByRole('tab', 'Historia').tap();
      await expect(screen.getByTestId('history-empty')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-02 the author voids an entry: reason is required, the row is struck through, the card is recomputed', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 3 });
      const s = await w.shipment([[p, 3]]);
      const r = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 2, { shipmentId: s })]);
      const id = r.body.receipts[0].id;
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(accepted(screen, p)).toContainText('2');
      await screen.getByRole('tab', 'Historia').tap();
      await screen.getByTestId(`history-row-${id}`).getByRole('button', 'Anuluj wpis').tap();
      const dlg = screen.getByTestId('void-dialog');
      await expect(dlg).toBeVisible();
      await dlg.getByRole('button', 'Anuluj wpis').tap(); // confirm without a reason
      await expect(dlg.getByRole('alert')).toContainText('Powód jest wymagany');
      await dlg.getByLabel('Powód anulowania').fill(`rcpt-${w.id}-ui-void`);
      await dlg.getByRole('button', 'Anuluj wpis').tap();
      await expect(dlg).toBeHidden();
      const row = screen.getByTestId(`history-row-${id}`);
      await expect(row).toHaveAttribute('data-voided', 'true');
      await expect(screen.getByTestId(`history-void-${id}`)).toContainText(`rcpt-${w.id}-ui-void`);
      await expect(row.getByRole('button', 'Anuluj wpis')).toBeHidden();
      await screen.getByRole('tab', 'Do odbioru').tap();
      await expect(accepted(screen, p)).toContainText('0');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-02 step 4 void buttons: other foreman no, MANAGER yes', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN', 'FOREMANB']);
    try {
      const p = w.position({ quantity: 3 });
      const s = await w.shipment([[p, 3]]);
      const id = (await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })])).body.receipts[0].id;
      await uiLogin(app, screen, w.users.FOREMANB!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByRole('tab', 'Historia').tap();
      await expect(screen.getByTestId(`history-row-${id}`)).toBeVisible();
      await expect(screen.getByTestId(`history-row-${id}`).getByRole('button', 'Anuluj wpis')).toBeHidden();
      await app.clearState();
      await uiLogin(app, screen, w.users.MANAGER!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByRole('tab', 'Historia').tap();
      await expect(screen.getByTestId(`history-row-${id}`).getByRole('button', 'Anuluj wpis')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-02 step 5 an entry older than the window has no void button for its author', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 3 });
      const s = await w.shipment([[p, 3]]);
      const id = (await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })])).body.receipts[0].id;
      psql(`UPDATE goods_receipts SET occurred_at = occurred_at - interval '25 hours' WHERE id = ${id};`);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByRole('tab', 'Historia').tap();
      await expect(screen.getByTestId(`history-row-${id}`)).toBeVisible();
      await expect(screen.getByTestId(`history-row-${id}`).getByRole('button', 'Anuluj wpis')).toBeHidden();
      expect((await voidReceipt(w.t('FOREMAN'), id, 'late')).status).toBe(403);
    } finally {
      await w.cleanup();
    }
  });
});

// keep the unused helpers referenced so a later edit does not trip the type-checker
void post;
export type _W = World;
