import { mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { ev, eventually, get, makeWorld, notificationsOf, openReceipt, receive, uiLogin } from './support.js';

// FOR-11-05 Part B (UI): B5 photos, B6 deep link and drawers, B7 bottom bar / locale, B8 states and responsiveness.
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-05-goods-receipt/test-cases.md.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()); photo fixtures are generated under .tmp/ and removed.
// Environment: the image storage is enabled but its GCS credentials are expired, so a real upload answers 500;
// the photo tests mock the media endpoint with browser.route (see FINDINGS.md).

const PNG_1X1 = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const TMP = 'tests/for11/05-goods-receipt/.tmp';
function pngFiles(runId: string, n: number): string[] {
  mkdirSync(TMP, { recursive: true });
  return Array.from({ length: n }, (_, i) => {
    const p = `${TMP}/${runId}-${i}.png`;
    writeFileSync(p, PNG_1X1);
    return p;
  });
}
const cleanFiles = () => rmSync(TMP, { recursive: true, force: true });
const sheet = (screen: any) => screen.getByTestId('receive-sheet');

describe('FOR-11-05 UI: photos', { tags: ['for-11-05', 'ui', 'photos'] }, () => {
  test('TC-B5-02 a failed photo upload keeps the saved event and retries only the photo', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 3 });
      await w.shipment([[p, 3]]);
      const [file] = pngFiles(w.id, 1);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${p}`).tap();
      let mode: 'fail' | 'ok' = 'fail';
      const hits: string[] = [];
      await browser.route('**/api/goods-receipts/*/media', async (route: any) => {
        hits.push(mode);
        if (mode === 'fail') await route.fulfill({ status: 500, contentType: 'application/json', json: { message: 'boom' } });
        else await route.fulfill({ status: 200, contentType: 'application/json', json: [] });
      });
      await sheet(screen).getByTestId('photo-input').setInputFiles([file!]);
      await expect(sheet(screen).getByTestId('photo-picker').getByRole('img')).toBeVisible();
      await sheet(screen).getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen).getByTestId('photo-upload-failed')).toBeVisible();
      const rows = (await get(`/api/goods-receipts?projectId=${w.projectId}&positionId=${p}`, w.t('MANAGER'))).body.content;
      expect(rows).toHaveLength(1);
      mode = 'ok';
      await sheet(screen).getByRole('button', 'Ponów wysyłanie zdjęć').tap();
      await expect(sheet(screen)).toBeHidden();
      expect(hits).toEqual(['fail', 'ok']);
      expect((await get(`/api/goods-receipts?projectId=${w.projectId}&positionId=${p}`, w.t('MANAGER'))).body.content).toHaveLength(1);
    } finally {
      cleanFiles();
      await w.cleanup();
    }
  });

  test('TC-B5-03 photo picker: only JPEG/PNG/WebP, at most 8 per request with a limit hint', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 3 });
      await w.shipment([[p, 3]]);
      const files = pngFiles(w.id, 9);
      mkdirSync(TMP, { recursive: true });
      writeFileSync(`${TMP}/${w.id}-doc.pdf`, '%PDF-1.4 test');
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${p}`).tap();
      const picker = sheet(screen).getByTestId('photo-picker');
      await expect(sheet(screen).getByTestId('photo-input')).toHaveAttribute('accept', 'image/jpeg,image/png,image/webp');
      await sheet(screen).getByTestId('photo-input').setInputFiles([`${TMP}/${w.id}-doc.pdf`]);
      await expect(picker.getByRole('img')).toHaveCount(0);
      await sheet(screen).getByTestId('photo-input').setInputFiles(files);
      await expect(picker.getByRole('img')).toHaveCount(8);
      await expect(picker).toContainText('Maksymalnie 8 zdjęć');
      await expect(picker.getByRole('button', 'Dodaj zdjęcie')).toBeDisabled();
    } finally {
      cleanFiles();
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 UI: deep link, upcoming deliveries and ledger panels', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B6-01 ?position= highlights the card; an unknown id does not break the page', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pd = w.position({ quantity: 2 });
      await w.shipment([[pd, 2]]);
      const other = w.position({ quantity: 2 });
      await w.shipment([[other, 2]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId, `?position=${pd}`);
      await expect(screen.getByTestId(`receipt-card-${pd}`)).toHaveAttribute('data-highlighted', 'true');
      await expect(screen.getByTestId(`receipt-card-${other}`)).not.toHaveAttribute('data-highlighted', 'true');
      await openReceipt(app, screen, browser, w.projectId, '?position=999999999');
      await expect(screen.getByRole('heading', 'Odbiór dostaw')).toBeVisible();
      await expect(screen.getByTestId(`receipt-card-${pd}`)).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  // BUG-1 (FINDINGS.md): the ?position= deep link highlights the card but never opens the position history.
  // R10.6: the deep link SHALL "scroll to and highlight the card and open its history". The page keeps the
  // "To receive" tab and renders no history for the position (GoodsReceiptPage / PositionReceiptCard).
  test('TC-B6-01 step 1 BUG-1 (FINDINGS.md): ?position= opens the position history', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const pd = w.position({ quantity: 2 });
      const sd = await w.shipment([[pd, 2]]);
      const r = await receive(w.t('FOREMAN'), [ev(pd, 'ACCEPTED', 1, { shipmentId: sd })]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId, `?position=${pd}`);
      await expect(screen.getByTestId(`history-row-${r.body.receipts[0].id}`)).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B6-01 step 3 the discrepancy notification in the bell opens the same deep link', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const pd = w.position({ quantity: 2 });
      const sd = await w.shipment([[pd, 2]]);
      expect((await receive(w.t('FOREMAN'), [ev(pd, 'ACCEPTED', 3, { shipmentId: sd })])).status).toBe(201);
      const n = await eventually(async () => (await notificationsOf(w.t('MANAGER'))).find((x) => x.type === 'notification.receipt.discrepancy'));
      expect(n).toBeTruthy();
      await uiLogin(app, screen, w.users.MANAGER!);
      await app.open('/');
      await screen.getByTestId('notification-bell').tap();
      await expect(screen.getByTestId(`notification-type-${n!.id}`)).toHaveText('Rozbieżność przy odbiorze dostawy');
      expect(await screen.getByTestId('notification-list').textContent()).not.toContain('notification.receipt');
      await screen.getByTestId(`notification-link-${n!.id}`).tap();
      await expect(browser).toHaveURL(new RegExp(`/deliveries/receipt\\?position=${pd}`));
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B6-02 upcoming deliveries: FOREMAN gets a Receive button that opens the deep link; BUYER does not', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p2 = w.position({ quantity: 2 });
      await w.shipment([[p2, 2]], { etaDate: new Date(Date.now() + 2 * 86400_000).toISOString().slice(0, 10) });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open('/deliveries/upcoming');
      await expect(screen.getByTestId(`receive-${p2}`)).toBeVisible();
      await screen.getByTestId(`receive-${p2}`).tap();
      await expect(browser).toHaveURL(new RegExp(`/deliveries/receipt\\?position=${p2}`));
      await app.clearState();
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/upcoming?project=${w.projectId}`);
      await expect(screen.getByTestId(`delivery-card-${p2}`)).toBeVisible();
      await expect(screen.getByTestId(`receive-${p2}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B6-02 step 2 a RECEIVED position is not in the upcoming list', async ({ app, screen }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 1 });
      const eta = new Date(Date.now() + 2 * 86400_000).toISOString().slice(0, 10);
      const s = await w.shipment([[p, 1]], { etaDate: eta });
      await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open('/deliveries/upcoming');
      await expect(screen.getByTestId(`delivery-card-${p}`)).toBeHidden();
      await expect(screen.getByTestId(`receive-${p}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 UI: ledger panel in the planner', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B6-03 MANAGER opens a position in the planner and sees the ledger summary and the events', async ({ app, screen }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 3 });
      const s = await w.shipment([[p, 3]]);
      const r = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      await uiLogin(app, screen, w.users.MANAGER!);
      await app.open(`/deliveries?project=${w.projectId}&groupBy=NONE`);
      await screen.getByTestId(`row-actions-${p}`, { visible: true }).tap();
      await screen.getByTestId("row-action-EDIT").tap();
      const panel = screen.getByTestId('ledger-panel');
      await expect(panel).toBeVisible();
      await expect(panel.getByTestId('ledger-summary')).toContainText('Przyjęto 1 z 3');
      await expect(panel.getByTestId(`ledger-event-${r.body.receipts[0].id}`)).toBeVisible();
      expect(await panel.textContent()).not.toMatch(/zł|PLN|EUR|€/i);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B6-03 step 3 a position without events shows the empty ledger state', async ({ app, screen }) => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 3 });
      await w.shipment([[p, 3]]);
      await uiLogin(app, screen, w.users.MANAGER!);
      await app.open(`/deliveries?project=${w.projectId}&groupBy=NONE`);
      await screen.getByTestId(`row-actions-${p}`, { visible: true }).tap();
      await screen.getByTestId("row-action-EDIT").tap();
      await expect(screen.getByTestId('ledger-panel')).toBeVisible();
      await expect(screen.getByTestId('ledger-empty')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 UI: navigation and locale', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B7-01 foreman bottom bar at 360 px has five slots in order and the receipt slot is active on the screen', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      await browser.setViewport({ width: 360, height: 800 });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByRole('heading', 'Odbiór dostaw')).toBeVisible();
      const labels: string[] = await browser.evaluate(() => {
        const bars = Array.from(document.querySelectorAll('nav')).filter((n) => {
          const r = n.getBoundingClientRect();
          return r.width > 0 && r.top > window.innerHeight / 2;
        });
        const bar = bars[bars.length - 1];
        return bar ? Array.from(bar.querySelectorAll('a')).map((a) => (a.textContent ?? '').trim()) : [];
      });
      expect(labels, JSON.stringify(labels)).toHaveLength(5);
      expect(labels[2]).toContain('Nadchodzące dostawy');
      expect(labels[3]).toContain('Odbiór dostaw');
      expect(labels[4]).toContain('Dodaj wydatek');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B7-02 the screen is localized: PL and RU titles, tabs and no raw goodsReceipt.* keys', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 2 });
      await w.shipment([[p, 2]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByRole('heading', 'Odbiór dostaw')).toBeVisible();
      await screen.getByTestId(`receive-btn-${p}`).tap();
      await sheet(screen).getByRole('button', 'Brak').tap();
      await sheet(screen).getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen).getByRole('alert')).toContainText('Komentarz jest wymagany');
      expect(await browser.evaluate(() => document.body.innerText)).not.toMatch(/goodsReceipt\./);
      await browser.evaluate(() => {
        localStorage.setItem('i18nextLng', 'ru');
        localStorage.setItem('foremen-locale', 'ru');
      });
      await app.open('/deliveries/receipt');
      await expect(screen.getByRole('heading', 'Приёмка поставок')).toBeVisible();
      await expect(screen.getByRole('tab', 'История')).toBeVisible();
      expect(await browser.evaluate(() => document.body.innerText)).not.toMatch(/goodsReceipt\./);
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 UI: states and responsiveness', { tags: ['for-11-05', 'ui'] }, () => {
  test('TC-B8-01 an API error shows a retry; the retry loads the list; an empty project shows the empty state', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 2 });
      await w.shipment([[p, 2]]);
      await uiLogin(app, screen, w.users.FOREMAN!);
      let broken = true;
      await browser.route('**/api/goods-receipts/to-receive**', async (route: any) => {
        if (broken) await route.fulfill({ status: 500, contentType: 'application/json', json: { message: 'boom' } });
        else await route.continue();
      });
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByText('Nie udało się załadować danych odbioru')).toBeVisible();
      broken = false;
      await screen.getByRole('button', 'Spróbuj ponownie').tap();
      await expect(screen.getByTestId(`receipt-card-${p}`)).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B8-01 step 2 a project without pending positions shows "Nic do odbioru"', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByText('Nic do odbioru')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B8-01 step 3 at 360 px: no horizontal scroll, controls are at least 44 px high', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      await browser.setViewport({ width: 360, height: 800 });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await expect(screen.getByTestId(`receipt-card-${p}`)).toBeVisible();
      const m = await browser.evaluate(() => ({ sw: document.documentElement.scrollWidth, cw: document.documentElement.clientWidth }));
      expect(m.sw).toBeLessThanOrEqual(m.cw);
      for (const id of [`receive-btn-${p}`, `accept-all-${s}`]) {
        const box = await screen.getByTestId(id).boundingBox();
        expect(box!.height, id).toBeGreaterThanOrEqual(44);
      }
      for (const name of ['Do odbioru', 'Historia']) {
        const box = await screen.getByRole('tab', name).boundingBox();
        expect(box!.height, name).toBeGreaterThanOrEqual(44);
      }
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B8-01 step 4 desktop 1280 px: same functionality', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 2 });
      await w.shipment([[p, 2]]);
      await browser.setViewport({ width: 1280, height: 900 });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openReceipt(app, screen, browser, w.projectId);
      await screen.getByTestId(`receive-btn-${p}`).tap();
      await sheet(screen).getByRole('button', 'Zapisz').tap();
      await expect(sheet(screen)).toBeHidden();
      await expect.poll(() => w.positionRow(p).status).toBe('RECEIVED');
    } finally {
      await w.cleanup();
    }
  });
});
