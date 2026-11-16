import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { day, errMatches, eventually, get, http, makeWorld, notesOfType, ok, positionOf, post, psql, put, uuid, type World } from './support.js';
// FOR-11-06 Part A: A8 (payment link), A9 (position added by the buyer, "unpaid"), A10 (quick purchase).
// Source: test-cases.md of FOR-11-06. Repeatability: run-id world per test, cleanup in `finally`.
// A buyer-added position is WITH_BUYER at once, so it is the cheap fixture for orders in this file.
const flags = (id: number) => psql(`SELECT added_by_buyer || '|' || client_unpaid FROM purchase_positions WHERE id = ${id};`);
/** A DRAFT order with one buyer-added line; returns ids. */
async function orderWithLine(w: World, o: { price?: number; sale?: number } = {}) {
  const pos = await w.buyerAdded({ qty: 5, price: o.price ?? 60, sale: o.sale ?? 100 });
  expect(pos.status, pos.text).toBe(201);
  const positionId = pos.body.id as number;
  const order = await w.draftOrder([[positionId, 5, o.price ?? 60]]);
  expect(order.status, order.text).toBe(200);
  return { positionId, orderId: order.body.id as number };
}
const link = (w: World, orderId: number, paymentLink: string | null, tag: 'BUYER' | 'FINANCIER' = 'BUYER') =>
  put(`/api/external-orders/${orderId}/payment-link`, w.t(tag), { paymentLink });
describe('FOR-11-06 API: buyer flows', { tags: ['for-11-06', 'api'] }, () => {
  test('TC-A8-01 the buyer sets the payment link on a DRAFT order', async () => {
    const w = await makeWorld();
    try {
      const { orderId } = await orderWithLine(w);
      const url = `https://pay.example.com/${w.id}/1`;
      const r = await link(w, orderId, url);
      expect(r.status, r.text).toBe(200);
      expect(r.body.paymentLink).toBe(url);
      expect((await get(`/api/external-orders/${orderId}`, w.t('BUYER'))).body.paymentLink).toBe(url);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A8-02 only http(s) links are accepted; null clears', async () => {
    const w = await makeWorld();
    try {
      const { orderId } = await orderWithLine(w);
      for (const bad of ['javascript:alert(1)', 'ftp://pay.example.com/x', '/relative/path', 'pay.example.com']) {
        expect(errMatches(await link(w, orderId, bad), 400, 'error.externalOrder.paymentLink.invalid'), bad).toBeNull();
      }
      expect((await link(w, orderId, `https://pay.example.com/${'a'.repeat(1001)}`)).status).toBe(400);
      await link(w, orderId, `https://pay.example.com/${w.id}/2`);
      const clear = await link(w, orderId, null);
      expect(clear.status, clear.text).toBe(200);
      expect(clear.body.paymentLink ?? null).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A8-03 the link is locked after payment and after cancellation', async () => {
    const w = await makeWorld();
    try {
      const a = await orderWithLine(w);
      expect((await post(`/api/external-orders/${a.orderId}/send`, w.t('BUYER'))).status).toBe(200);
      expect((await post(`/api/external-orders/${a.orderId}/mark-paid`, w.t('FINANCIER'), {})).status).toBe(200);
      expect(errMatches(await link(w, a.orderId, `https://pay.example.com/${w.id}/3`), 409, 'error.externalOrder.paymentLink.locked')).toBeNull();
      const b = await orderWithLine(w);
      expect((await post(`/api/external-orders/${b.orderId}/cancel`, w.t('BUYER'), { reason: w.tag('rcpt') })).status).toBe(200);
      expect(errMatches(await link(w, b.orderId, `https://pay.example.com/${w.id}/4`), 409, 'error.externalOrder.paymentLink.locked')).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A8-04 changing the link of a sent order notifies the financiers; the same link again does not', async () => {
    const w = await makeWorld();
    try {
      const { orderId } = await orderWithLine(w);
      expect((await post(`/api/external-orders/${orderId}/send`, w.t('BUYER'))).status).toBe(200);
      const url = `https://pay.example.com/${w.id}/5`;
      expect((await link(w, orderId, url)).status).toBe(200);
      const first = await eventually(async () => {
        const n = (await notesOfType(w.t('FINANCIER'), 'notification.externalOrder.paymentLinkAdded'));
        return n.length ? n : undefined;
      });
      expect(first).toBeDefined();
      expect(first![0]!.deepLink).toContain(`order=${orderId}`);
      expect((await link(w, orderId, url)).status).toBe(200);
      await new Promise((res) => setTimeout(res, 1500));
      expect(await notesOfType(w.t('FINANCIER'), 'notification.externalOrder.paymentLinkAdded')).toHaveLength(first!.length);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A8-05 CLIENT, FOREMAN and WORKER cannot read or set the link', async () => {
    const w = await makeWorld({ tags: ['WORKER'] });
    try {
      const { orderId } = await orderWithLine(w);
      await link(w, orderId, `https://pay.example.com/${w.id}/6`);
      for (const tag of ['CLIENT', 'FOREMAN', 'WORKER'] as const) {
        expect((await get(`/api/external-orders/${orderId}`, w.t(tag))).status, tag).toBe(403);
        expect((await link(w, orderId, `https://pay.example.com/${w.id}/7`, tag as any)).status, tag).toBe(403);
      }
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-01 buyer-added position: WITH_BUYER, added_by_buyer, client_unpaid, AGREED, line in the draft order', async () => {
    const w = await makeWorld();
    try {
      const draft = await w.draftOrder([]);
      expect(draft.status, draft.text).toBe(200);
      const r = await w.buyerAdded({ qty: 4, price: 60, sale: 100, orderId: draft.body.id });
      expect(r.status, r.text).toBe(201);
      const pos = await positionOf(w, r.body.id);
      expect(pos.status).toBe('WITH_BUYER');
      expect(pos.sourcingPath).toBe('AGREED');
      expect(flags(r.body.id)).toBe('true|true');
      const order = (await get(`/api/external-orders/${draft.body.id}`, w.t('BUYER'))).body;
      expect(order.lines.map((l: any) => l.positionId)).toContain(r.body.id);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-02 the payment gate is intact and the endpoint is closed to other roles', async () => {
    const w = await makeWorld();
    try {
      const planned = await w.plannedPosition();
      const t = await post(`/api/purchase-positions/${planned}/transition`, w.admin, { target: 'WITH_BUYER' });
      expect(t.status, t.text).toBe(409);
      for (const tag of ['FOREMAN', 'CLIENT', 'FINANCIER'] as const) {
        const r = await http('POST', '/api/purchase-positions/buyer-added', w.t(tag), {
          projectId: w.projectId, plannedMaterialId: w.materialId, quantity: 1, plannedPurchasePrice: 10,
        });
        expect(r.status, tag).toBe(403);
      }
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-03 client and manager are notified, with no prices in the notification', async () => {
    const w = await makeWorld();
    try {
      const r = await w.buyerAdded({ price: 61.37, sale: 99 });
      expect(r.status, r.text).toBe(201);
      for (const tag of ['CLIENT', 'MANAGER'] as const) {
        const n = await eventually(async () => {
          const list = await notesOfType(w.t(tag), 'notification.position.addedByBuyer');
          return list.length ? list : undefined;
        });
        expect(n, tag).toBeDefined();
        expect(JSON.stringify(n)).not.toContain('61.37');
      }
      const cl = (await notesOfType(w.t('CLIENT'), 'notification.position.addedByBuyer'))[0]!;
      expect(cl.deepLink ?? '').toContain('positionApprovals');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-04 the tail of the cycle runs; the unpaid flag stays through to RECEIVED', async () => {
    const w = await makeWorld();
    try {
      const { positionId, orderId } = await orderWithLine(w);
      expect((await post(`/api/external-orders/${orderId}/send`, w.t('BUYER'))).status).toBe(200);
      expect((await post(`/api/external-orders/${orderId}/mark-paid`, w.t('FINANCIER'), {})).status).toBe(200);
      expect((await positionOf(w, positionId)).status).toBe('SUPPLIER_PAID');
      const shipmentId = await w.shipment([[positionId, 5]]);
      expect((await positionOf(w, positionId)).status).toBe('IN_DELIVERY');
      const rec = await post('/api/goods-receipts', w.t('FOREMAN'), { events: [{ positionId, kind: 'ACCEPTED', quantity: 5, shipmentId, clientRequestId: uuid() }] });
      expect(rec.status, rec.text).toBe(201);
      expect((await positionOf(w, positionId)).status).toBe('RECEIVED');
      expect(flags(positionId)).toBe('true|true');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-05 A received but unpaid position is not hidden by hideDelivered; a normal received one is', async () => {
    const w = await makeWorld();
    try {
      const { positionId } = await orderWithLine(w);
      psql(`UPDATE purchase_positions SET status = 'RECEIVED' WHERE id = ${positionId};`);
      const normal = w.paidPosition(2);
      psql(`UPDATE purchase_positions SET status = 'RECEIVED' WHERE id = ${normal};`);
      const q = encodeURIComponent(`project.id==${w.projectId}`);
      const hidden = await get(`/api/purchase-positions?query=${q}&size=100&hideDelivered=true`, w.t('MANAGER'));
      expect(hidden.status, hidden.text).toBe(200);
      const ids = (hidden.body.content as any[]).map((x) => x.id);
      expect(ids).toContain(positionId);
      expect(ids).not.toContain(normal);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-07 a proforma may include a WITH_BUYER..RECEIVED unpaid position; paying it clears the flag, cancelling does not', async () => {
    const w = await makeWorld();
    try {
      const { positionId } = await orderWithLine(w, { sale: 100 });
      const draft = await post('/api/client-payments/materials', w.t('MANAGER'), { projectId: w.projectId, positionIds: [positionId], dueDate: day(7) });
      expect(draft.status, draft.text).toBe(200);
      expect((await positionOf(w, positionId)).status).toBe('WITH_BUYER');
      const issued = await post(`/api/client-payments/${draft.body.id}/issue`, w.t('MANAGER'), { language: 'pl' });
      if (issued.status === 502) test.skip('ENV: PDF storage unavailable (issue answers 502)');
      expect(issued.status, issued.text).toBe(200);
      expect((await positionOf(w, positionId)).status).toBe('WITH_BUYER');
      const gross = Number((await get(`/api/client-payments/${draft.body.id}`, w.t('MANAGER'))).body.amountGross);
      const t = w.statementRow(gross, 'unpaid clear');
      const pay = await post(`/api/bank/transactions/${t}/links`, w.t('FINANCIER'), { kind: 'PROFORMA_PAYMENT', amount: gross, clientPaymentId: draft.body.id });
      expect(pay.status, pay.text).toBe(201);
      expect(flags(positionId)).toBe('true|false');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-08 the client does not see the sale price of an unpaid buyer-added position before the proforma', async () => {
    const w = await makeWorld();
    try {
      const { positionId } = await orderWithLine(w);
      const view = await get(`/api/position-approvals/client/projects/${w.projectId}`, w.t('CLIENT'));
      expect(view.status, view.text).toBe(200);
      const row = JSON.stringify(view.body).includes(`"positionId":${positionId}`);
      expect(row).toBe(false);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A9-09 a buyer-added position can be cancelled before dispatch', async () => {
    const w = await makeWorld();
    try {
      const r = await w.buyerAdded();
      const c = await post(`/api/purchase-positions/${r.body.id}/transition`, w.t('MANAGER'), { target: 'CANCELLED' });
      expect(c.status, c.text).toBe(200);
      expect((await positionOf(w, r.body.id)).status).toBe('CANCELLED');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A10-01 quick purchase: one DRAFT order per seller, planned prices', async () => {
    const w = await makeWorld();
    try {
      const a = (await w.buyerAdded({ price: 40, sale: 100 })).body.id as number;
      const b = (await w.buyerAdded({ price: 50, sale: 100 })).body.id as number;
      const r = await post('/api/external-orders/from-positions', w.t('BUYER'), { positionIds: [a, b], sellerId: w.sellerId });
      expect(r.status, r.text).toBe(200);
      expect(r.body.orders).toHaveLength(1);
      expect(r.body.orders[0].lineCount).toBe(2);
      const order = (await get(`/api/external-orders/${r.body.orders[0].id}`, w.t('BUYER'))).body;
      expect(order.status).toBe('DRAFT');
      expect(order.lines.map((l: any) => Number(l.unitPurchaseNet)).sort()).toEqual([40, 50]);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A10-02 positions of two projects answer 409 mixedProjects and create nothing', async () => {
    const w = await makeWorld();
    try {
      const a = (await w.buyerAdded()).body.id as number;
      const other = (await w.buyerAdded({ projectId: w.otherProjectId })).body.id as number;
      const before = Number(psql(`SELECT COUNT(*) FROM external_orders WHERE project_id IN (${w.projectId}, ${w.otherProjectId});`));
      const r = await post('/api/external-orders/from-positions', w.admin, { positionIds: [a, other], sellerId: w.sellerId });
      expect(errMatches(r, 409, 'error.quickPurchase.mixedProjects')).toBeNull();
      expect(Number(psql(`SELECT COUNT(*) FROM external_orders WHERE project_id IN (${w.projectId}, ${w.otherProjectId});`))).toBe(before);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A10-03 no seller for a position answers 409 sellerRequired and rolls back', async () => {
    const w = await makeWorld();
    try {
      const a = (await w.buyerAdded()).body.id as number;
      const before = Number(psql(`SELECT COUNT(*) FROM external_orders WHERE project_id = ${w.projectId};`));
      const r = await post('/api/external-orders/from-positions', w.t('BUYER'), { positionIds: [a] });
      expect(errMatches(r, 409, 'error.quickPurchase.sellerRequired')).toBeNull();
      expect(Number(psql(`SELECT COUNT(*) FROM external_orders WHERE project_id = ${w.projectId};`))).toBe(before);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A10-04 a planned purchase price above the sale price needs acknowledgement', async () => {
    const w = await makeWorld();
    try {
      const a = (await w.buyerAdded({ price: 150, sale: 100 })).body.id as number;
      const r = await post('/api/external-orders/from-positions', w.t('BUYER'), { positionIds: [a], sellerId: w.sellerId });
      expect(errMatches(r, 409, 'error.externalOrder.line.aboveCeiling')).toBeNull();
      const yes = await post('/api/external-orders/from-positions', w.t('BUYER'), { positionIds: [a], sellerId: w.sellerId, acknowledgedPositionIds: [a] });
      expect(yes.status, yes.text).toBe(200);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A10-05 only WITH_BUYER positions; CLIENT and FOREMAN get 403', async () => {
    const w = await makeWorld();
    try {
      const accepted = await w.acceptedPosition(1, 10);
      const r = await post('/api/external-orders/from-positions', w.t('BUYER'), { positionIds: [accepted], sellerId: w.sellerId });
      expect(r.status, r.text).toBe(409);
      const ok1 = (await w.buyerAdded()).body.id as number;
      for (const tag of ['CLIENT', 'FOREMAN'] as const) {
        expect((await post('/api/external-orders/from-positions', w.t(tag), { positionIds: [ok1], sellerId: w.sellerId })).status, tag).toBe(403);
      }
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A10-06 after the financier pays, the buyer is sent to the shipment form with the order', async () => {
    const w = await makeWorld();
    try {
      const { orderId } = await orderWithLine(w);
      expect((await post(`/api/external-orders/${orderId}/send`, w.t('BUYER'))).status).toBe(200);
      expect((await post(`/api/external-orders/${orderId}/mark-paid`, w.t('FINANCIER'), {})).status).toBe(200);
      const n = await eventually(async () => {
        const list = await notesOfType(w.t('BUYER'), 'notification.externalOrder.paid');
        return list.length ? list : undefined;
      });
      expect(n).toBeDefined();
      expect(n![0]!.deepLink).toContain('/deliveries/shipments?new=1');
      expect(n![0]!.deepLink).toContain(`order=${orderId}`);
    } finally {
      await w.cleanup();
    }
  });
});
