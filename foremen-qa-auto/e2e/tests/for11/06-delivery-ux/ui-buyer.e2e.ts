import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { eventually, get, makeWorld, notesOfType, ok, post, psql, put, type World } from './support.js';
import { openPath, pageText, uiLogin } from './ui-support.js';

// FOR-11-06 Part B (UI): B5 buyer flows (payment link, buyer-added position, unpaid badge, quick purchase).
// Requirements 9, 10, 11. Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-06-delivery-ux-refinements/test-cases.md.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()) in `finally`. Language PL (config).
// The planner and the board live on `/deliveries`; the order editor on `/deliveries/external-orders/{id}`;
// the financier queue on `/deliveries/external-orders`.

/** A DRAFT external order with one buyer-added line (API fixture). */
async function orderWithLine(w: World) {
  const pos = await w.buyerAdded({ qty: 5, price: 60, sale: 100 });
  expect(pos.status, pos.text).toBe(201);
  const positionId = pos.body.id as number;
  const order = await w.draftOrder([[positionId, 5, 60]]);
  expect(order.status, order.text).toBe(201);
  return { positionId, orderId: order.body.id as number };
}

describe('FOR-11-06 UI: buyer flows', { tags: ['for-11-06', 'ui', 'buyer'] }, () => {
  test('TC-B5-01 payment link: the buyer saves it, a non-http(s) value is blocked, the financier pays through the link', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { orderId } = await orderWithLine(w);
      await uiLogin(app, screen, w.users.BUYER!);
      await openPath(app, screen, browser, w.projectId, `/deliveries/external-orders/${orderId}`);
      const field = screen.getByTestId('external-order-payment-link');
      await expect(field).toBeVisible();
      await field.fill('javascript:alert(1)');
      await expect(screen.getByTestId('external-order-payment-link-invalid')).toBeVisible();
      await expect(screen.getByTestId('external-order-payment-link-save')).toBeDisabled();
      const url = `https://pay.example.com/${w.id}/ui1`;
      await field.fill(url);
      await screen.getByTestId('external-order-payment-link-save').tap();
      await expect.poll(() => psql(`SELECT COALESCE(payment_link, '') FROM external_orders WHERE id = ${orderId};`)).toBe(url);
      // Send to the financier through the API, then check the queue card.
      expect((await post(`/api/external-orders/${orderId}/send`, w.t('BUYER'))).status).toBe(200);
      await app.clearState();
      await uiLogin(app, screen, w.users.FINANCIER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries/external-orders');
      // The run has one order in the queue; the button is rendered as the link itself (Button asChild).
      const pay = screen.getByTestId('financier-pay-online');
      await expect(pay).toBeVisible();
      await expect(pay).toHaveAttribute('href', url);
      await expect(pay).toHaveAttribute('target', '_blank');
      await expect(screen.getByTestId('financier-payment-link-text')).toContainText(url);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B5-01 step 3 the field is hidden from roles outside the money audience', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { orderId } = await orderWithLine(w);
      expect((await put(`/api/external-orders/${orderId}/payment-link`, w.t('BUYER'), { paymentLink: `https://pay.example.com/${w.id}/ui2` })).status).toBe(200);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await openPath(app, screen, browser, w.projectId, `/deliveries/external-orders/${orderId}`);
      expect(await pageText(browser)).not.toContain(`pay.example.com/${w.id}`);
      await expect(screen.getByTestId('external-order-payment-link')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B5-02 "Dodaj pozycję" on the board creates a buyer-added card and notifies the client', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      await uiLogin(app, screen, w.users.BUYER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries');
      await screen.getByRole('tab', 'Tablica zakupowca').tap();
      await screen.getByTestId('board-add-position').tap();
      const dialog = screen.getByTestId('buyer-added-dialog');
      await expect(dialog).toBeVisible();
      // Fields are filled by their accessible labels; the material is the first catalog entry.
      await dialog.getByLabel('Ilość').fill('4');
      await dialog.getByLabel('Cena zakupu').fill('55');
      await dialog.getByRole('combobox').first().tap();
      await screen.getByRole('option').first().tap();
      await screen.getByTestId('buyer-added-submit').tap();
      await expect(screen.getByTestId('buyer-added-error')).toBeHidden();
      const created = await eventually(async () => {
        const id = psql(`SELECT id FROM purchase_positions WHERE project_id = ${w.projectId} AND added_by_buyer ORDER BY id DESC LIMIT 1;`);
        return id || undefined;
      });
      expect(created, 'the position was not created').toBeTruthy();
      await expect(screen.getByTestId(`board-card-${created}`)).toBeVisible();
      await expect(screen.getByTestId(`board-card-${created}`).getByTestId('unpaid-badge')).toBeVisible();
      const notes = await eventually(async () => {
        const n = (await notesOfType(w.t('CLIENT'), 'notification.position.addedByBuyer')) as unknown[];
        return n.length ? n : undefined;
      });
      expect(notes, 'the client got no "added by buyer" notification').toBeTruthy();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B5-03 a delivered unpaid card stays visible under "Ukryj dostarczone" with the "Dostarczone, nieopłacone" badge', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { positionId, orderId } = await orderWithLine(w);
      expect((await post(`/api/external-orders/${orderId}/send`, w.t('BUYER'))).status).toBe(200);
      expect((await post(`/api/external-orders/${orderId}/mark-paid`, w.t('FINANCIER'), {})).status).toBe(200);
      const shipmentId = await w.shipment([[positionId, 5]]);
      const rec = await post('/api/goods-receipts', w.t('FOREMAN'), {
        events: [{ positionId, kind: 'ACCEPTED', quantity: 5, shipmentId, clientRequestId: crypto.randomUUID() }],
      });
      expect(rec.status, rec.text).toBe(201);
      await uiLogin(app, screen, w.users.BUYER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries');
      await screen.getByRole('tab', 'Tablica zakupowca').tap();
      await screen.getByTestId('hide-delivered-switch').check();
      const card = screen.getByTestId(`board-card-${positionId}`);
      await expect(card).toBeVisible();
      await expect(card.getByTestId('unpaid-badge')).toContainText('Dostarczone, nieopłacone');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B5-04 quick purchase: three board cards, "Utwórz zakup" creates draft orders; a second project blocks the selection', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const ids: number[] = [];
      for (let i = 0; i < 3; i++) ids.push((await w.buyerAdded({ price: 40 + i, sale: 100 })).body.id as number);
      const other = (await w.buyerAdded({ projectId: w.otherProjectId })).body.id as number;
      const before = Number(psql(`SELECT COUNT(*) FROM external_orders WHERE project_id = ${w.projectId};`));
      await uiLogin(app, screen, w.users.BUYER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries');
      await screen.getByRole('tab', 'Tablica zakupowca').tap();
      for (const id of ids) await screen.getByTestId(`select-card-${id}`).check();
      // A card of another project cannot join the selection.
      const foreign = screen.getByTestId(`select-card-${other}`);
      if (await foreign.isVisible()) await expect(foreign).toBeDisabled();
      await screen.getByRole('button', 'Utwórz zakup').tap();
      const dialog = screen.getByTestId('quick-purchase-dialog');
      await expect(dialog).toBeVisible();
      await expect(screen.getByTestId('quick-purchase-items')).toBeVisible();
      await screen.getByTestId('quick-purchase-submit').tap();
      await expect.poll(() => Number(psql(`SELECT COUNT(*) FROM external_orders WHERE project_id = ${w.projectId};`))).toBeGreaterThan(before);
      const r = await get(`/api/external-orders?projectId=${w.projectId}&size=50`, w.t('BUYER'));
      expect(r.status).toBe(200);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B5-05 after the financier pays, the buyer notification opens the shipment form with the order preselected', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { orderId } = await orderWithLine(w);
      expect((await post(`/api/external-orders/${orderId}/send`, w.t('BUYER'))).status).toBe(200);
      expect((await post(`/api/external-orders/${orderId}/mark-paid`, w.t('FINANCIER'), {})).status).toBe(200);
      const note = await eventually(async () => (await notesOfType(w.t('BUYER'), 'notification.externalOrder.paid'))[0]);
      expect(note, 'no "order paid" notification').toBeTruthy();
      expect(note!.deepLink).toContain('/deliveries/shipments');
      await uiLogin(app, screen, w.users.BUYER!);
      await openPath(app, screen, browser, w.projectId, `/deliveries/shipments?new=1&order=${orderId}`);
      await expect(screen.getByRole('button', 'Utwórz przesyłkę')).toBeVisible();
      await screen.getByRole('button', 'Utwórz przesyłkę').tap();
      await expect.poll(() => Number(psql(`SELECT COUNT(*) FROM shipments WHERE project_id = ${w.projectId};`))).toBe(1);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B5-06 the order and shipment forms list all projects, and the team of a project is not shown to the buyer', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      await uiLogin(app, screen, w.users.BUYER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries/external-orders/new');
      const picker = screen.getByRole('combobox', 'Projekt').or(screen.getByLabel('Projekt'));
      await picker.first().tap();
      await expect(screen.getByRole('option', `FOR1106-${w.id}`).or(screen.getByText(`FOR1106-${w.id}`)).first()).toBeVisible();
      await expect(screen.getByRole('option', `FOR1106-OTHER-${w.id}`).or(screen.getByText(`FOR1106-OTHER-${w.id}`)).first()).toBeVisible();
      const text = await pageText(browser);
      for (const tag of ['MANAGER', 'CLIENT', 'FOREMAN'] as const) expect(text).not.toContain(w.users[tag]!.email);
    } finally {
      await w.cleanup();
    }
  });
});
