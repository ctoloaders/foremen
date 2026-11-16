import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { ev, get, makeWorld, msgOf, MSG, notificationsOf, post, psql, put, receive, voidReceipt, type World } from './support.js';

// FOR-11-05 Part A: A3 (void), A4 (re-delivery), A5 (shipment closing).
// Repeatability: run-id in every name + cleanup of every row (incl. goods_receipts) in `finally`.

const shipOf = async (w: World, id: number) => (await get(`/api/shipments/${id}`, w.t('BUYER'))).body;
const ledger = async (w: World, pid: number) => (await get(`/api/purchase-positions/${pid}/ledger`, w.t('MANAGER'))).body;
const fullPut = (s: any, patch: Record<string, unknown> = {}) => ({
  deliveryMethod: s.deliveryMethod, carrierCode: s.carrierCode, trackingNumber: s.trackingNumber, trackingUrl: s.trackingUrl,
  shopOrderLink: s.shopOrderLink, driverName: s.driverName, driverPhone: s.driverPhone, note: s.note, ...patch,
});

describe('FOR-11-05 API: void a receipt', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A3-01 author voids within the window: ledger recomputed, status back to IN_DELIVERY, row kept', async () => {
    const w = await makeWorld();
    try {
      const pg = w.position({ quantity: 2 });
      const sg = await w.shipment([[pg, 2]]);
      const r1 = await receive(w.t('FOREMAN'), [ev(pg, 'ACCEPTED', 1, { shipmentId: sg })]);
      expect(r1.body.positions[0].positionStatus).toBe('PARTIALLY_RECEIVED');
      const id = r1.body.receipts[0].id;
      const reason = `rcpt-${w.id}-void`;
      const v = await voidReceipt(w.t('FOREMAN'), id, reason);
      expect(v.status, JSON.stringify(v.body)).toBe(200);
      expect(v.body.receipt.voidedAt).toBeTruthy();
      expect(v.body.receipt.voidReason).toBe(reason);
      expect(v.body.position.ledger.acceptedNet).toBe(0);
      expect(v.body.position.positionStatus).toBe('IN_DELIVERY');
      expect(v.body.redeliveryKept).toBe(false);
      expect(w.positionRow(pg)).toMatchObject({ status: 'IN_DELIVERY' });
      expect(Number(w.positionRow(pg).accepted || 0)).toBe(0);
      const url = `/api/goods-receipts?projectId=${w.projectId}&positionId=${pg}`;
      expect((await get(url, w.t('MANAGER'))).body.content).toHaveLength(0);
      const all = (await get(`${url}&includeVoided=true`, w.t('MANAGER'))).body.content;
      expect(all).toHaveLength(1);
      expect(all[0].voidedAt).toBeTruthy();
      expect(psql(`SELECT count(*) FROM goods_receipts WHERE id = ${id};`)).toBe('1');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A3-02 second void is 409 alreadyVoided; empty / blank / missing reason is 400', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const f = w.t('FOREMAN');
      const a = await receive(f, [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      const id = a.body.receipts[0].id;
      expect((await voidReceipt(f, id, 'first')).status).toBe(200);
      const twice = await voidReceipt(f, id, 'second');
      expect(twice.status).toBe(409);
      expect(msgOf(twice)).toContain(MSG.alreadyVoided);
      const b = await receive(f, [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      const id2 = b.body.receipts[0].id;
      for (const reason of ['', '   ', null]) {
        const r = await voidReceipt(f, id2, reason);
        expect(r.status, `reason=${JSON.stringify(reason)}`).toBe(400);
      }
      const row = await get(`/api/goods-receipts/${id2}`, w.t('MANAGER'));
      expect(row.body.voidedAt ?? null).toBeNull();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A3-03 void rights: other foreman 403, MANAGER 200, author out of the window 403, ADMIN 200, canVoid flags', async () => {
    const w = await makeWorld(['FOREMANB']);
    try {
      const p = w.position({ quantity: 5 });
      const s = await w.shipment([[p, 5]]);
      const f = w.t('FOREMAN');
      const r2 = (await receive(f, [ev(p, 'ACCEPTED', 1, { shipmentId: s })])).body.receipts[0].id;
      const flags = async (tag: 'FOREMAN' | 'FOREMANB' | 'MANAGER' | 'BUYER') =>
        (await get(`/api/goods-receipts/${r2}`, w.t(tag))).body.canVoid;
      expect(await flags('FOREMAN')).toBe(true);
      expect(await flags('MANAGER')).toBe(true);
      expect(await flags('BUYER')).toBe(false);
      const other = await voidReceipt(w.t('FOREMANB'), r2, 'not mine');
      expect(other.status).toBe(403);
      expect(msgOf(other)).toContain(MSG.voidForbidden);
      expect((await voidReceipt(w.t('MANAGER'), r2, 'manager void')).status).toBe(200);

      const r3 = (await receive(f, [ev(p, 'ACCEPTED', 1, { shipmentId: s })])).body.receipts[0].id;
      psql(`UPDATE goods_receipts SET occurred_at = occurred_at - interval '25 hours' WHERE id = ${r3};`);
      expect((await get(`/api/goods-receipts/${r3}`, f)).body.canVoid).toBe(false);
      const late = await voidReceipt(f, r3, 'too late');
      expect(late.status).toBe(403);
      expect(msgOf(late)).toContain(MSG.voidForbidden);
      expect((await voidReceipt(w.t('ADMIN'), r3, 'admin void')).status).toBe(200);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A3-04 voiding the origin event cancels an untouched re-delivery; an edited one is kept; a closed shipment stays closed', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 3 });
      const s = await w.shipment([[p, 3]]);
      const m = await receive(w.t('FOREMAN'), [
        ev(p, 'ACCEPTED', 2, { shipmentId: s }),
        ev(p, 'MISSING', 1, { shipmentId: s, comment: `rcpt-${w.id}-m` }),
      ]);
      expect(m.status, JSON.stringify(m.body)).toBe(201);
      const rd = m.body.redeliveryShipmentIds[0];
      expect(rd).toBeTruthy();
      const missingId = m.body.receipts.find((x: any) => x.kind === 'MISSING').id;
      const v = await voidReceipt(w.t('MANAGER'), missingId, 'qa');
      expect(v.status, JSON.stringify(v.body)).toBe(200);
      expect(v.body.redeliveryKept).toBe(false);
      expect((await shipOf(w, rd)).status).toBe('CANCELLED');
      // the shipment closed by the receipt is not reopened (documented limitation, R8.6)
      expect((await shipOf(w, s)).status).toBe('DELIVERED');

      // second case: BUYER edits the re-delivery (ETA), then the origin is voided -> kept
      const p2 = w.position({ quantity: 3 });
      const s2 = await w.shipment([[p2, 3]]);
      const m2 = await receive(w.t('FOREMAN'), [
        ev(p2, 'ACCEPTED', 2, { shipmentId: s2 }),
        ev(p2, 'MISSING', 1, { shipmentId: s2, comment: 'x' }),
      ]);
      const rd2 = m2.body.redeliveryShipmentIds[0];
      const cur = await shipOf(w, rd2);
      const edit = await put(`/api/shipments/${rd2}`, w.t('BUYER'), fullPut(cur, { etaDate: '2099-01-01' }));
      expect(edit.status, JSON.stringify(edit.body)).toBe(200);
      const v2 = await voidReceipt(w.t('MANAGER'), m2.body.receipts.find((x: any) => x.kind === 'MISSING').id, 'qa');
      expect(v2.status).toBe(200);
      expect(v2.body.redeliveryKept).toBe(true);
      expect((await shipOf(w, rd2)).status).not.toBe('CANCELLED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A3-05 voiding the ACCEPTED event of a RECEIVED position returns it to IN_DELIVERY', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const a = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 2, { shipmentId: s })]);
      expect(a.body.positions[0].positionStatus).toBe('RECEIVED');
      const v = await voidReceipt(w.t('MANAGER'), a.body.receipts[0].id, 'qa');
      expect(v.status, JSON.stringify(v.body)).toBe(200);
      expect(v.body.position.positionStatus).toBe('IN_DELIVERY');
      expect(w.positionRow(p).status).toBe('IN_DELIVERY');
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 API: re-delivery of missing units', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A4-01 re-delivery quantity is min(remainder, released), not the whole outstanding', async () => {
    const w = await makeWorld();
    try {
      const pb = w.position({ quantity: 3 });
      const sb = await w.shipment([[pb, 2]]);
      const r = await receive(w.t('FOREMAN'), [
        ev(pb, 'ACCEPTED', 1, { shipmentId: sb }),
        ev(pb, 'RETURNED', 1, { shipmentId: sb, fromAccepted: false, comment: `rcpt-${w.id}-1` }),
      ]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      expect(r.body.redeliveryShipmentIds).toHaveLength(1);
      const rd = await shipOf(w, r.body.redeliveryShipmentIds[0]);
      expect(rd.deliveryMethod).toBe('SHOP_DELIVERY');
      expect(rd.etaDate ?? null).toBeNull();
      expect(rd.lines).toHaveLength(1);
      expect(rd.lines[0].quantity).toBe(1);
      const l = (await ledger(w, pb)).ledger;
      expect(l).toMatchObject({ outstanding: 2, remainder: 1 });
      expect((await ledger(w, pb)).positionStatus).toBe('IN_DELIVERY');
      // remaining capacity of 1 is open for a manual shipment, 2 are not
      const bad = await post('/api/shipments', w.t('BUYER'), {
        projectId: w.projectId, deliveryMethod: 'CARRIER', carrierCode: 'DPD', trackingNumber: `DPD${w.id.toUpperCase()}77`,
        lines: [{ positionId: pb, quantity: 2 }],
      });
      expect(bad.status).toBeGreaterThanOrEqual(400);
      expect(msgOf(bad)).toContain('Ilość przekracza zamówioną ilość pozycji');
      const good = await w.shipment([[pb, 1]]);
      expect(good).toBeGreaterThan(0);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A4-02 one re-delivery shipment per position per request', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 4 });
      const s = await w.shipment([[p, 4]]);
      const r = await receive(w.t('FOREMAN'), [
        ev(p, 'RETURNED', 1, { shipmentId: s, fromAccepted: false, comment: 'x' }),
        ev(p, 'MISSING', 2, { shipmentId: s, comment: 'y' }),
      ]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      expect(r.body.redeliveryShipmentIds).toHaveLength(1);
      const rd = await shipOf(w, r.body.redeliveryShipmentIds[0]);
      expect(rd.lines).toHaveLength(1);
      expect(rd.lines[0].quantity).toBe(3);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A4-03 re-delivery sends no "shipped" notification, but the discrepancy one', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 3 });
      const s = await w.shipment([[p, 3]]);
      await new Promise((res) => setTimeout(res, 1500));
      const shipped = async () => (await notificationsOf(w.t('MANAGER'))).filter((n) => n.type === 'notification.shipment.shipped').length;
      const before = await shipped();
      const r = await receive(w.t('FOREMAN'), [ev(p, 'MISSING', 1, { shipmentId: s, comment: 'x' })]);
      expect(r.body.redeliveryShipmentIds).toHaveLength(1);
      await new Promise((res) => setTimeout(res, 2500));
      expect(await shipped()).toBe(before);
      for (const tag of ['MANAGER', 'FOREMAN'] as const) {
        const list = await notificationsOf(w.t(tag));
        const disc = list.filter((n) => n.type === 'notification.receipt.discrepancy');
        expect(disc.length, tag).toBe(tag === 'MANAGER' ? 1 : 0);
      }
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A4-04 the cycle shortage -> re-delivery -> receipt closes', async () => {
    const w = await makeWorld();
    try {
      const pb = w.position({ quantity: 3 });
      const sb = await w.shipment([[pb, 2]]);
      const r = await receive(w.t('FOREMAN'), [
        ev(pb, 'ACCEPTED', 1, { shipmentId: sb }),
        ev(pb, 'RETURNED', 1, { shipmentId: sb, fromAccepted: false, comment: 'x' }),
      ]);
      const rdId = r.body.redeliveryShipmentIds[0];
      const a = await receive(w.t('FOREMAN'), [ev(pb, 'ACCEPTED', 1, { shipmentId: rdId })]);
      expect(a.status, JSON.stringify(a.body)).toBe(201);
      expect(a.body.positions[0].ledger.acceptedNet).toBe(2);
      expect((await shipOf(w, rdId)).status).toBe('DELIVERED');
      const manual = await w.shipment([[pb, 1]]);
      const last = await receive(w.t('FOREMAN'), [ev(pb, 'ACCEPTED', 1, { shipmentId: manual })]);
      expect(last.body.positions[0].positionStatus).toBe('RECEIVED');
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 API: order-line limit with released units', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A4-05 the external-order line limit counts released units: re-delivery keeps the order and later shipments are capped', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 3 });
      const eo = Number(psql(
        `INSERT INTO external_orders (project_id, seller_id, status, reference_number, created_by)
         VALUES (:'pid'::bigint, 1, 'PAID', :'ref', 'qa') RETURNING id;`,
        { pid: String(w.projectId), ref: `FOR1105-${w.id}-EO` },
      ).split('\n')[0]);
      psql(
        `INSERT INTO external_order_lines (external_order_id, position_id, quantity, unit_purchase_net, created_by)
         VALUES (:'oid'::bigint, :'p'::bigint, 3, 1, 'qa');`,
        { oid: String(eo), p: String(p) },
      );
      const s = await w.shipment([[p, 3]], { externalOrderId: eo });
      const r = await receive(w.t('FOREMAN'), [ev(p, 'MISSING', 1, { shipmentId: s, comment: 'x' })]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      const rd = await shipOf(w, r.body.redeliveryShipmentIds[0]);
      expect(rd.externalOrderId).toBe(eo);
      // the freed unit is already carried by the re-delivery, so one more unit exceeds the line / cap
      const more = await post('/api/shipments', w.t('BUYER'), {
        projectId: w.projectId, deliveryMethod: 'CARRIER', carrierCode: 'DPD', trackingNumber: `DPD${w.id.toUpperCase()}91`,
        externalOrderId: eo, lines: [{ positionId: p, quantity: 1 }],
      });
      expect(more.status).toBe(409);
      expect(msgOf(more)).toMatch(/Ilość przekracza/);
      // after voiding the MISSING event the re-delivery is cancelled and the unit is free again under the order line
      expect((await voidReceipt(w.t('MANAGER'), r.body.receipts[0].id, 'qa')).status).toBe(200);
      const ok = await post('/api/shipments', w.t('BUYER'), {
        projectId: w.projectId, deliveryMethod: 'CARRIER', carrierCode: 'DPD', trackingNumber: `DPD${w.id.toUpperCase()}92`,
        externalOrderId: eo, lines: [{ positionId: p, quantity: 1 }],
      });
      expect(ok.status, JSON.stringify(ok.body)).toBe(409); // the original shipment still carries all 3 units
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 API: shipment closing and guards', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A5-01 a shipment closes only when every line is resolved (MANUAL, deliveredAt, nextCheckAt null)', async () => {
    const w = await makeWorld();
    try {
      const pu1 = w.position({ quantity: 2 });
      const pu2 = w.position({ quantity: 1 });
      const s = await w.shipment([[pu1, 2], [pu2, 1]]);
      expect((await receive(w.t('FOREMAN'), [ev(pu1, 'ACCEPTED', 2, { shipmentId: s })])).status).toBe(201);
      expect((await shipOf(w, s)).status).not.toBe('DELIVERED');
      const m = await receive(w.t('FOREMAN'), [ev(pu2, 'MISSING', 1, { shipmentId: s, comment: 'x' })]);
      expect(m.status, JSON.stringify(m.body)).toBe(201);
      const sh = await shipOf(w, s);
      expect(sh.status).toBe('DELIVERED');
      expect(sh.statusSource).toBe('MANUAL');
      expect(sh.deliveredAt).toBeTruthy();
      expect(sh.nextCheckAt ?? null).toBeNull();
      expect(JSON.stringify(sh.events)).toContain('Closed by goods receipt');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A5-02 a return from accepted does not resolve the line', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const r = await receive(w.t('FOREMAN'), [
        ev(p, 'ACCEPTED', 1, { shipmentId: s }),
        ev(p, 'RETURNED', 1, { fromAccepted: true, comment: 'x' }),
      ]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      expect((await shipOf(w, s)).status).not.toBe('DELIVERED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A5-03 a closed shipment is immutable (PUT 409 terminal)', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 1 });
      const s = await w.shipment([[p, 1]]);
      expect((await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })])).status).toBe(201);
      const cur = await shipOf(w, s);
      const r = await put(`/api/shipments/${s}`, w.t('BUYER'), fullPut(cur, { etaDate: '2099-01-01' })); // terminal: status/ETA/lines are frozen (driver fields are not)
      expect(r.status).toBe(409);
      expect(msgOf(r)).toContain(MSG.shipmentTerminal);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A5-04 / TC-A5-05 a shipment with receipts cannot be cancelled; after voiding it can and the position returns to SUPPLIER_PAID', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 2 });
      const s = await w.shipment([[p, 2]]);
      const a = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      const blocked = await post(`/api/shipments/${s}/cancel`, w.t('BUYER'));
      expect(blocked.status).toBe(409);
      expect(msgOf(blocked)).toContain(MSG.shipmentHasReceipts);
      expect(w.positionRow(p).status).toBe('PARTIALLY_RECEIVED');
      expect((await voidReceipt(w.t('MANAGER'), a.body.receipts[0].id, 'qa')).status).toBe(200);
      const ok = await post(`/api/shipments/${s}/cancel`, w.t('BUYER'));
      expect(ok.status, JSON.stringify(ok.body)).toBe(200);
      expect(w.positionRow(p).status).toBe('SUPPLIER_PAID');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A5-04 cancelling a terminal (DELIVERED) shipment is refused with terminal', async () => {
    const w = await makeWorld();
    try {
      const p = w.position({ quantity: 1 });
      const s = await w.shipment([[p, 1]]);
      const a = await receive(w.t('FOREMAN'), [ev(p, 'ACCEPTED', 1, { shipmentId: s })]);
      await voidReceipt(w.t('MANAGER'), a.body.receipts[0].id, 'qa');
      const r = await post(`/api/shipments/${s}/cancel`, w.t('BUYER'));
      expect(r.status).toBe(409);
      expect(msgOf(r)).toContain(MSG.shipmentTerminal);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A5-06 a position with receipts but no shipment cannot be put on a new shipment', async () => {
    const w = await makeWorld();
    try {
      const pe = w.position({ path: 'CLIENT_OWN', quantity: 2 });
      expect((await receive(w.t('FOREMAN'), [ev(pe, 'ACCEPTED', 2)])).status).toBe(201);
      expect((await receive(w.t('FOREMAN'), [ev(pe, 'RETURNED', 1, { fromAccepted: true, comment: 'x' })])).status).toBe(201);
      const r = await post('/api/shipments', w.t('BUYER'), {
        projectId: w.projectId, deliveryMethod: 'CARRIER', carrierCode: 'DPD', trackingNumber: `DPD${w.id.toUpperCase()}55`,
        lines: [{ positionId: pe, quantity: 1 }],
      });
      expect(r.status).toBe(409);
      expect(msgOf(r)).toContain(MSG.shipmentPositionHasReceipts);
    } finally {
      await w.cleanup();
    }
  });
});
