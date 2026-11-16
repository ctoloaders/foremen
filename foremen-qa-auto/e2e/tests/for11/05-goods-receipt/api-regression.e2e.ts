import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { day, ev, get, makeWorld, msgOf, post, put, receive, uiLogin } from './support.js';

// FOR-11-05 "Regression" group: R-01..R-07 (R-08 needs an enabled tracker and scheduler, not automated).
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-05-goods-receipt/test-cases.md.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()) in `finally`.

const carrierBody = (w: { projectId: number; id: string }, lines: Array<[number, number]>, extra: Record<string, unknown> = {}) => ({
  projectId: w.projectId, deliveryMethod: 'CARRIER', carrierCode: 'DPD', trackingNumber: `DPD${w.id.toUpperCase()}${Math.floor(Math.random() * 1e6)}`,
  lines: lines.map(([positionId, quantity]) => ({ positionId, quantity })), ...extra,
});

describe('FOR-11-05 regression: earlier specs are unchanged', { tags: ['for-11-05', 'regression'] }, () => {
  test('TC-R-01 shipment line limits for a position without receipts are as in FOR-11-04', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 3 });
      const wb = w.position({ quantity: 3, status: 'WITH_BUYER' });
      const b = w.t('BUYER');
      const over = await post('/api/shipments', b, carrierBody(w, [[p, 4]]));
      expect(over.status).toBe(409);
      expect(msgOf(over)).toContain('Ilość przekracza zamówioną ilość pozycji');
      const dup = await post('/api/shipments', b, carrierBody(w, [[p, 1], [p, 1]]));
      expect(dup.status).toBeGreaterThanOrEqual(400);
      expect(msgOf(dup)).toContain('Ta sama pozycja została dodana więcej niż raz');
      const empty = await post('/api/shipments', b, carrierBody(w, []));
      expect(empty.status).toBeGreaterThanOrEqual(400);
      const stage = await post('/api/shipments', b, carrierBody(w, [[wb, 1]]));
      expect(stage.status).toBe(409);
      expect(msgOf(stage)).toContain('Pozycja nie jest w etapie umożliwiającym wysyłkę');
      expect((await post('/api/shipments', b, carrierBody(w, [[p, 3]]))).status).toBe(201);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-02 cancelling a shipment without receipts returns the position to SUPPLIER_PAID', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      expect(w.positionRow(p).status).toBe('IN_DELIVERY');
      expect((await post(`/api/shipments/${s}/cancel`, w.t('BUYER'))).status).toBe(200);
      expect(w.positionRow(p).status).toBe('SUPPLIER_PAID');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-03 a carrier/manual DELIVERED status does not change the position stage', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const cur = (await get(`/api/shipments/${s}`, w.t('BUYER'))).body;
      const r = await put(`/api/shipments/${s}`, w.t('BUYER'), {
        deliveryMethod: cur.deliveryMethod, carrierCode: cur.carrierCode, trackingNumber: cur.trackingNumber, status: 'DELIVERED',
      });
      expect(r.status, JSON.stringify(r.body)).toBe(200);
      expect(r.body.status).toBe('DELIVERED');
      expect(w.positionRow(p)).toMatchObject({ status: 'IN_DELIVERY' });
      expect(Number(w.positionRow(p).accepted || 0)).toBe(0);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-04 the manual transition matrix is not widened and acceptedQuantity is read-only', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 1 });
      const s = await w.shipment([[p, 1]]);
      expect((await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })])).status).toBe(201);
      expect(w.positionRow(p).status).toBe('RECEIVED');
      for (const target of ['IN_DELIVERY', 'SUPPLIER_PAID', 'PARTIALLY_RECEIVED']) {
        const r = await post(`/api/purchase-positions/${p}/transition`, w.t('ADMIN'), { target });
        expect(r.status, `RECEIVED -> ${target}`).toBeGreaterThanOrEqual(400);
      }
      expect(w.positionRow(p).status).toBe('RECEIVED');
      const cur = (await get(`/api/purchase-positions/${p}`, w.t('ADMIN'))).body;
      const upd = await put(`/api/purchase-positions/${p}`, w.t('ADMIN'), { ...cur, acceptedQuantity: 99 });
      expect(Number(w.positionRow(p).accepted)).toBe(1);
      expect(upd.status === 200 || upd.status >= 400).toBe(true);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-05 upcoming: a fully received position disappears and carries no downtime risk', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]], { etaDate: day(3) });
      const url = `/api/purchase-positions/upcoming?days=14&projectId=${w.projectId}`;
      const before = await get(url, w.t('MANAGER'));
      expect(before.status).toBe(200);
      expect(before.body.some((x: any) => x.positionId === p)).toBe(true);
      expect((await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 2, { shipmentId: s })])).status).toBe(201);
      const after = await get(url, w.t('MANAGER'));
      expect(after.body.some((x: any) => x.positionId === p)).toBe(false);
      const overlay = await get(`/api/purchase-positions/schedule-overlay?projectId=${w.projectId}`, w.t('MANAGER'));
      expect(overlay.status).toBe(200);
      // no schedule in this project: the overlay answers with no risk for the received position
      expect(JSON.stringify(overlay.body)).not.toMatch(/"downtimeRisk":true/);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-06 money, ordered quantity and payment state are untouched by receipts (incl. surplus and returns)', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const keys = (b: any) => Object.fromEntries(Object.entries(b).filter(([k]) => /price|ordered|margin|amount|vat|paid|cost/i.test(k)));
      const before = keys((await get(`/api/purchase-positions/${p}`, w.t('ADMIN'))).body);
      expect(Object.keys(before).length).toBeGreaterThan(0);
      await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 3, { shipmentId: s })]); // surplus
      await receive(w.t('FOREMAN'), [ev(p, 'RETURNED', 1, { fromAccepted: true, comment: 'x' })]);
      const after = keys((await get(`/api/purchase-positions/${p}`, w.t('ADMIN'))).body);
      expect(after).toEqual(before);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-07 BUYER/MANAGER still open /deliveries/shipments, /upcoming and /external-orders', async ({ app, screen }) => {
    const w = await makeWorld();
    try {
      for (const tag of ['BUYER', 'MANAGER'] as const) {
        await uiLogin(app, screen, w.users[tag]!);
        for (const path of ['/deliveries/shipments', '/deliveries/upcoming', '/deliveries/external-orders']) {
          await app.open(path);
          await expect(screen.getByText('Brak dostępu')).toBeHidden();
          await expect(screen.getByRole('heading', { level: 1, visible: true }).first()).toBeVisible();
        }
        await app.clearState();
      }
    } finally {
      await w.cleanup();
    }
  });
});
