import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  day, ev, eventually, get, ledgerOf, makeWorld, msgOf, MSG, notificationsOf, post, psql, receive, uuid, voidReceipt, type World,
} from './support.js';

// FOR-11-05 Part A: A1 (record an event), A2 (batch + idempotent retry).
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-05-goods-receipt/test-cases.md.
// Environment: carrier STUB is not registered on the stack (see FINDINGS.md), so shipments are created by BUYER
// with carrier DPD (link-out, never fetched). Repeatability: run-id in every name + cleanup of every row in `finally`.

const setEta = (shipmentId: number, d: string) => psql(`UPDATE shipments SET eta_date = :'d'::date WHERE id = :sid;`, { d, sid: String(shipmentId) });
const L = async (w: World, pid: number, tag: 'MANAGER' | 'ADMIN' = 'MANAGER') => (await ledgerOf(w.t(tag), pid)).body;
const receiptsOf = async (w: World, pid: number, extra = '') =>
  (await get(`/api/goods-receipts?projectId=${w.projectId}&positionId=${pid}${extra}`, w.t('MANAGER'))).body.content as any[];

describe('FOR-11-05 API: record goods receipt', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A1-01 full receipt of a shipment in one event: RECEIVED, shipment closed, no discrepancy notification', async () => {
    const w = await makeWorld();
    try {
      const pa = w.position({ quantity: 3 });
      const sa = await w.shipment([[pa, 3]]);
      const list = await get(`/api/goods-receipts/to-receive?projectId=${w.projectId}`, w.t('FOREMAN'));
      expect(list.status).toBe(200);
      const item = list.body.find((x: any) => x.positionId === pa);
      expect(item.positionStatus).toBe('IN_DELIVERY');
      expect(item.ledger).toMatchObject({ cap: 3, inFlight: 3, outstanding: 3, acceptedNet: 0 });
      expect(item.shipments.find((s: any) => s.shipmentId === sa)).toMatchObject({ quantity: 3, unresolvedQuantity: 3 });

      const r = await receive(w.t('FOREMAN'), [ev(pa, 'ACCEPTED', 3, { shipmentId: sa })]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      expect(r.body.receipts[0]).toMatchObject({ kind: 'ACCEPTED', quantity: 3, shipmentId: sa, receivedById: w.users.FOREMAN!.id });
      expect(Math.abs(Date.now() - Date.parse(r.body.receipts[0].occurredAt))).toBeLessThan(60_000);
      expect(r.body.positions[0].positionStatus).toBe('RECEIVED');
      expect(r.body.positions[0].ledger).toMatchObject({ acceptedNet: 3, outstanding: 0, surplus: 0, inFlight: 0 });
      expect(r.body.redeliveryShipmentIds).toEqual([]);

      expect(w.positionRow(pa)).toMatchObject({ status: 'RECEIVED', accepted: '3.000' });
      const ship = await get(`/api/shipments/${sa}`, w.t('BUYER'));
      expect(ship.body.status).toBe('DELIVERED');
      expect(ship.body.deliveredAt).toBeTruthy();
      await new Promise((res) => setTimeout(res, 1500));
      for (const tag of ['MANAGER', 'BUYER'] as const) {
        expect((await notificationsOf(w.t(tag))).filter((n) => n.type === 'notification.receipt.discrepancy' && n.deepLink.endsWith(`=${pa}`))).toHaveLength(0);
      }
      const after = await get(`/api/goods-receipts/to-receive?projectId=${w.projectId}`, w.t('FOREMAN'));
      expect(after.body.find((x: any) => x.positionId === pa)).toBeUndefined();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-02 cap 3, shipment of 2: a batch ending with MISSING 1 rolls back (409), then ACCEPTED 1 + door RETURNED 1 is recorded', async () => {
    const w = await makeWorld();
    try {
      const pb = w.position({ quantity: 3 });
      const sb = await w.shipment([[pb, 2]]);
      const bad = await receive(w.t('FOREMAN'), [
        ev(pb, 'ACCEPTED', 1, { shipmentId: sb }),
        ev(pb, 'RETURNED', 1, { shipmentId: sb, fromAccepted: false, comment: `rcpt-${w.id}-1` }),
        ev(pb, 'MISSING', 1, { shipmentId: sb, comment: `rcpt-${w.id}-2` }),
      ]);
      expect(bad.status).toBe(409);
      expect(msgOf(bad)).toContain(MSG.exceedsInFlight);
      const l0 = await L(w, pb);
      expect(l0.positionStatus).toBe('IN_DELIVERY');
      expect(l0.ledger).toMatchObject({ acceptedNet: 0, inFlight: 2 });
      expect(await receiptsOf(w, pb)).toHaveLength(0);

      const ok = await receive(w.t('FOREMAN'), [
        ev(pb, 'ACCEPTED', 1, { shipmentId: sb }),
        ev(pb, 'RETURNED', 1, { shipmentId: sb, fromAccepted: false, comment: `rcpt-${w.id}-3` }),
      ]);
      expect(ok.status, JSON.stringify(ok.body)).toBe(201);
      const led = ok.body.positions[0];
      // Test-cases step 3 says inFlight=0, but the door return releases 1 unit and a re-delivery of 1 is created
      // (R8.2, A4-01), so the unit is in flight again: inFlight=1, remainder=1.
      expect(led.ledger).toMatchObject({ acceptedNet: 1, returned: 1, missing: 0, outstanding: 2, inFlight: 1, remainder: 1, surplus: 0 });
      expect(ok.body.redeliveryShipmentIds).toHaveLength(1);
      expect(await receiptsOf(w, pb)).toHaveLength(2);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-03 book example: ACCEPTED 1, RETURNED 1, MISSING 1 on a 3-unit shipment', async () => {
    const w = await makeWorld();
    try {
      const pa2 = w.position({ quantity: 3 });
      const sa2 = await w.shipment([[pa2, 3]]);
      const r = await receive(w.t('FOREMAN'), [
        ev(pa2, 'ACCEPTED', 1, { shipmentId: sa2 }),
        ev(pa2, 'RETURNED', 1, { shipmentId: sa2, fromAccepted: false, comment: `rcpt-${w.id}-r` }),
        ev(pa2, 'MISSING', 1, { shipmentId: sa2, comment: `rcpt-${w.id}-m` }),
      ]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      const pos = r.body.positions.find((p: any) => p.positionId === pa2);
      expect(pos.ledger).toMatchObject({ acceptedNet: 1, missing: 1, returned: 1, outstanding: 2, surplus: 0 });
      expect(pos.positionStatus).toBe('IN_DELIVERY'); // the re-delivery shipment sent it back to delivery (R8.3)
      expect(r.body.redeliveryShipmentIds).toHaveLength(1);
      const rd = await get(`/api/shipments/${r.body.redeliveryShipmentIds[0]}`, w.t('BUYER'));
      expect(rd.body.deliveryMethod).toBe('SHOP_DELIVERY');
      expect(['CREATED', 'IN_TRANSIT']).toContain(rd.body.status);
      expect(rd.body.lines).toHaveLength(1);
      expect(rd.body.lines[0].quantity).toBe(2);
      expect(rd.body.etaDate ?? null).toBeNull();
      expect(String(rd.body.note ?? '')).toContain('Re-delivery after receipt');
      expect(w.positionRow(pa2).status).toBe('IN_DELIVERY');
      const closed = await get(`/api/shipments/${sa2}`, w.t('BUYER'));
      expect(closed.body.status).toBe('DELIVERED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-04 receipt without shipment is split FIFO across shipments by ETA', async () => {
    const w = await makeWorld();
    try {
      const pc = w.position({ quantity: 4 });
      const sc1 = await w.shipment([[pc, 2]]);
      const sc2 = await w.shipment([[pc, 2]]);
      setEta(sc1, day(1));
      setEta(sc2, day(3));
      const r = await receive(w.t('FOREMAN'), [ev(pc, 'ACCEPTED', 3)]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      const rows = r.body.receipts.map((x: any) => [x.shipmentId, x.quantity]).sort((a: number[], b: number[]) => a[0]! - b[0]!);
      expect(rows).toEqual([[sc1, 2], [sc2, 1]]);
      expect((await get(`/api/shipments/${sc1}`, w.t('BUYER'))).body.status).toBe('DELIVERED');
      expect((await get(`/api/shipments/${sc2}`, w.t('BUYER'))).body.status).not.toBe('DELIVERED');
      const l = await L(w, pc);
      expect(l.ledger).toMatchObject({ acceptedNet: 3, inFlight: 1, outstanding: 1 });
      expect(l.positionStatus).toBe('PARTIALLY_RECEIVED');
      const r2 = await receive(w.t('FOREMAN'), [ev(pc, 'ACCEPTED', 1)]);
      expect(r2.status).toBe(201);
      expect(r2.body.receipts[0].shipmentId).toBe(sc2);
      expect(r2.body.positions[0].positionStatus).toBe('RECEIVED');
      expect((await get(`/api/shipments/${sc2}`, w.t('BUYER'))).body.status).toBe('DELIVERED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-05 CLIENT_OWN position without shipment: accept, return from accepted, accept again', async () => {
    const w = await makeWorld();
    try {
      const pe = w.position({ path: 'CLIENT_OWN', quantity: 2 });
      const a = await receive(w.t('FOREMAN'), [ev(pe, 'ACCEPTED', 2)]);
      expect(a.status, JSON.stringify(a.body)).toBe(201);
      expect(a.body.receipts[0].shipmentId ?? null).toBeNull();
      expect(w.positionRow(pe)).toMatchObject({ status: 'RECEIVED', accepted: '2.000' });
      const ret = await receive(w.t('FOREMAN'), [ev(pe, 'RETURNED', 1, { fromAccepted: true, comment: `rcpt-${w.id}-ret` })]);
      expect(ret.status, JSON.stringify(ret.body)).toBe(201);
      expect(ret.body.positions[0].ledger.acceptedNet).toBe(1);
      expect(ret.body.positions[0].positionStatus).toBe('PARTIALLY_RECEIVED');
      const b = await receive(w.t('FOREMAN'), [ev(pe, 'ACCEPTED', 1)]);
      expect(b.status, JSON.stringify(b.body)).toBe(201);
      expect(b.body.positions[0].positionStatus).toBe('RECEIVED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-06 over-delivery is accepted, surplus is shown and managers/buyers are notified (not the actor)', async () => {
    const w = await makeWorld(['MANAGER2', 'FINANCIER']);
    try {
      const pd = w.position({ quantity: 2 });
      const sd = await w.shipment([[pd, 2]]);
      const r = await receive(w.t('FOREMAN'), [ev(pd, 'ACCEPTED', 3, { shipmentId: sd })]);
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      expect(r.body.positions[0].ledger).toMatchObject({ acceptedNet: 3, surplus: 1, outstanding: 0 });
      expect(r.body.positions[0].positionStatus).toBe('RECEIVED');
      // test-cases says the surplus unit is split off with shipmentId=null; the product keeps one row on the
      // explicit shipment (R4.5 does not prescribe a split). Doc mismatch, not a product bug.
      expect(r.body.receipts.map((x: any) => [x.shipmentId, x.quantity])).toEqual([[sd, 3]]);
      const link = `/deliveries/receipt?position=${pd}`;
      const has = (list: any[]) => list.filter((n) => n.type === 'notification.receipt.discrepancy' && n.deepLink === link);
      for (const tag of ['MANAGER', 'MANAGER2', 'BUYER'] as const) {
        const got = await eventually(async () => {
          const l = has(await notificationsOf(w.t(tag)));
          return l.length ? l : undefined;
        });
        expect(got, `${tag} notification`).toHaveLength(1);
      }
      for (const tag of ['FOREMAN', 'FINANCIER', 'CLIENT'] as const) {
        if (!w.users[tag]) continue;
        expect(has(await notificationsOf(w.t(tag))), tag).toHaveLength(0);
      }
      expect(psql(`SELECT ordered_quantity FROM purchase_positions WHERE id = ${pd};`)).toMatch(/^2(\.0+)?$/);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-07 event form validation: quantity, comment, fromAccepted, foreign shipment, unknown ids, empty batch', async () => {
    const w = await makeWorld();
    try {
      const pf = w.position({ quantity: 5 });
      const sf = await w.shipment([[pf, 5]]);
      const other = w.position({ quantity: 2 });
      const so = await w.shipment([[other, 2]]);
      const f = w.t('FOREMAN');
      for (const q of [0, -1, 1.0001]) {
        const r = await receive(f, [ev(pf, 'ACCEPTED', q, { shipmentId: sf })]);
        expect(r.status, `q=${q}`).toBe(400);
        expect(msgOf(r)).toContain(MSG.quantityInvalid);
      }
      for (const c of [undefined, '   ']) {
        const r = await receive(f, [ev(pf, 'MISSING', 1, { shipmentId: sf, comment: c })]);
        expect(r.status).toBe(400);
        expect(msgOf(r)).toContain(MSG.commentRequired);
      }
      const rr = await receive(f, [ev(pf, 'RETURNED', 1, { shipmentId: sf })]);
      expect(rr.status).toBe(400);
      expect(msgOf(rr)).toContain(MSG.commentRequired);
      for (const kind of ['ACCEPTED', 'MISSING'] as const) {
        const r = await receive(f, [ev(pf, kind, 1, { shipmentId: sf, fromAccepted: true, comment: 'x' })]);
        expect(r.status, kind).toBe(400);
        expect(msgOf(r)).toContain(MSG.fromAcceptedInvalid);
      }
      const foreign = await receive(f, [ev(pf, 'ACCEPTED', 1, { shipmentId: so })]);
      expect(foreign.status).toBe(400);
      expect(msgOf(foreign)).toContain(MSG.shipmentMismatch);
      // R4.10 gives 400 shipment.mismatch for an unknown shipment too (test-cases says 404).
      expect((await receive(f, [ev(pf, 'ACCEPTED', 1, { shipmentId: 987654321 })])).status).toBe(400);
      expect((await receive(f, [ev(987654321, 'ACCEPTED', 1)])).status).toBe(404);
      expect((await receive(f, [])).status).toBe(400);
      expect(await receiptsOf(w, pf)).toHaveLength(0);
      // ACCEPTED without a comment is valid
      const okr = await receive(f, [ev(pf, 'ACCEPTED', 1, { shipmentId: sf })]);
      expect(okr.status, JSON.stringify(okr.body)).toBe(201);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-08 only IN_DELIVERY / PARTIALLY_RECEIVED accept events; RECEIVED allows only a return from accepted', async () => {
    const w = await makeWorld();
    try {
      const pw = w.position({ quantity: 2 }); // SUPPLIER_PAID, no shipment
      const pn = w.position({ quantity: 2, status: 'WITH_BUYER' });
      const f = w.t('FOREMAN');
      for (const p of [pw, pn]) {
        const r = await receive(f, [ev(p, 'ACCEPTED', 1)]);
        expect(r.status).toBe(409);
        expect(msgOf(r)).toContain(MSG.notReceivable);
      }
      const pe = w.position({ path: 'CLIENT_OWN', quantity: 2 });
      expect((await receive(f, [ev(pe, 'ACCEPTED', 2)])).status).toBe(201); // now RECEIVED
      const acc = await receive(f, [ev(pe, 'ACCEPTED', 1)]);
      expect(acc.status).toBe(409);
      expect(msgOf(acc)).toContain(MSG.notReceivable);
      const miss = await receive(f, [ev(pe, 'MISSING', 1, { comment: 'x' })]);
      expect(miss.status).toBe(409);
      expect(msgOf(miss)).toContain(MSG.notReceivable);
      const ret = await receive(f, [ev(pe, 'RETURNED', 1, { fromAccepted: true, comment: 'x' })]);
      expect(ret.status, JSON.stringify(ret.body)).toBe(201);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-09 MISSING and RETURNED bounds: in-flight and accepted never go negative', async () => {
    const w = await makeWorld();
    try {
      const pf = w.position({ quantity: 5 });
      const sf = await w.shipment([[pf, 5]]);
      const f = w.t('FOREMAN');
      const c = `rcpt-${w.id}`;
      const m6 = await receive(f, [ev(pf, 'MISSING', 6, { shipmentId: sf, comment: c })]);
      expect(m6.status).toBe(409);
      expect(msgOf(m6)).toContain(MSG.exceedsInFlight);
      const m5 = await receive(f, [ev(pf, 'MISSING', 5, { shipmentId: sf, comment: c })]);
      expect(m5.status, JSON.stringify(m5.body)).toBe(201);
      // position-level inFlight is 5 again: MISSING 5 released 5 units and a re-delivery of 5 was created
      expect(m5.body.positions[0].ledger).toMatchObject({ missing: 5 });
      expect(m5.body.redeliveryShipmentIds).toHaveLength(1);
      const m1 = await receive(f, [ev(pf, 'MISSING', 1, { shipmentId: sf, comment: c })]);
      expect(m1.status).toBe(409);
      expect(msgOf(m1)).toContain(MSG.exceedsInFlight);
      // void the MISSING 5 (author in window), then continue
      const missId = m5.body.receipts[0].id;
      expect((await voidReceipt(f, missId, `${c}-void`)).status).toBe(200);
      expect((await receive(f, [ev(pf, 'ACCEPTED', 2, { shipmentId: sf })])).status).toBe(201);
      const r3 = await receive(f, [ev(pf, 'RETURNED', 3, { fromAccepted: true, comment: c })]);
      expect(r3.status).toBe(409);
      expect(msgOf(r3)).toContain(MSG.returnExceedsAccepted);
      const r2 = await receive(f, [ev(pf, 'RETURNED', 2, { fromAccepted: true, comment: c })]);
      expect(r2.status, JSON.stringify(r2.body)).toBe(201);
      expect(r2.body.positions[0].ledger.acceptedNet).toBe(0);
      const door = await receive(f, [ev(pf, 'RETURNED', 3, { fromAccepted: false, comment: c })]);
      expect(door.status, JSON.stringify(door.body)).toBe(201);
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-05 API: batch and idempotency', { tags: ['for-11-05', 'api'] }, () => {
  test('TC-A2-01 retry with the same clientRequestId returns the same rows and writes nothing new', async () => {
    const w = await makeWorld();
    try {
      const pf = w.position({ quantity: 5 });
      const sf = await w.shipment([[pf, 5]]);
      const f = w.t('FOREMAN');
      const u = uuid();
      const first = await receive(f, [ev(pf, 'ACCEPTED', 2, { shipmentId: sf, clientRequestId: u })]);
      expect(first.status, JSON.stringify(first.body)).toBe(201);
      const id1 = first.body.receipts[0].id;
      const again = await receive(f, [ev(pf, 'ACCEPTED', 2, { shipmentId: sf, clientRequestId: u })]);
      expect(again.status).toBe(201);
      expect(again.body.receipts[0].id).toBe(id1);
      expect(Date.parse(again.body.receipts[0].occurredAt)).toBe(Date.parse(first.body.receipts[0].occurredAt));
      expect(again.body.positions[0].ledger).toEqual(first.body.positions[0].ledger);
      const changed = await receive(f, [ev(pf, 'ACCEPTED', 4, { shipmentId: sf, clientRequestId: u })]);
      expect(changed.status).toBe(201);
      expect(changed.body.receipts[0]).toMatchObject({ id: id1, quantity: 2 });
      expect(await receiptsOf(w, pf)).toHaveLength(1);
      expect((await L(w, pf)).ledger.acceptedNet).toBe(2);
      // mixed batch: first stored, second new
      const u2 = uuid();
      const mixed = await receive(f, [
        ev(pf, 'ACCEPTED', 2, { shipmentId: sf, clientRequestId: u }),
        ev(pf, 'ACCEPTED', 1, { shipmentId: sf, clientRequestId: u2 }),
      ]);
      expect(mixed.status, JSON.stringify(mixed.body)).toBe(201);
      expect(mixed.body.receipts).toHaveLength(2);
      expect(await receiptsOf(w, pf)).toHaveLength(2);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A2-02 a split receipt is returned with both parts on retry', async () => {
    const w = await makeWorld();
    try {
      const pc = w.position({ quantity: 4 });
      const s1 = await w.shipment([[pc, 2]]);
      const s2 = await w.shipment([[pc, 2]]);
      setEta(s1, day(1));
      setEta(s2, day(3));
      const u = uuid();
      const a = await receive(w.t('FOREMAN'), [ev(pc, 'ACCEPTED', 3, { clientRequestId: u })]);
      expect(a.body.receipts).toHaveLength(2);
      const b = await receive(w.t('FOREMAN'), [ev(pc, 'ACCEPTED', 3, { clientRequestId: u })]);
      expect(b.status).toBe(201);
      expect(b.body.receipts.map((x: any) => x.id).sort()).toEqual(a.body.receipts.map((x: any) => x.id).sort());
      expect(await receiptsOf(w, pc)).toHaveLength(2);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A2-03 batch atomicity: the first failing event rolls the whole batch back', async () => {
    const w = await makeWorld();
    try {
      const pf = w.position({ quantity: 5 });
      const sf = await w.shipment([[pf, 5]]);
      const pw = w.position({ quantity: 2 });
      const f = w.t('FOREMAN');
      const before = await L(w, pf);
      const bad1 = await receive(f, [ev(pf, 'ACCEPTED', 1, { shipmentId: sf }), ev(pf, 'MISSING', 99, { shipmentId: sf, comment: 'x' })]);
      expect(bad1.status).toBe(409);
      expect(msgOf(bad1)).toContain(MSG.exceedsInFlight);
      expect(await L(w, pf)).toEqual(before);
      expect(await receiptsOf(w, pf)).toHaveLength(0);
      expect(w.positionRow(pf).accepted).toMatch(/^(0(\.0+)?)?$/);
      const bad2 = await receive(f, [ev(pf, 'ACCEPTED', 1, { shipmentId: sf }), ev(pw, 'ACCEPTED', 1)]);
      expect(bad2.status).toBe(409);
      expect(msgOf(bad2)).toContain(MSG.notReceivable);
      expect(await receiptsOf(w, pf)).toHaveLength(0);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A2-04 events of a batch are applied in order', async () => {
    const w = await makeWorld();
    try {
      const pf = w.position({ quantity: 5 });
      const sf = await w.shipment([[pf, 5]]);
      const f = w.t('FOREMAN');
      expect((await receive(f, [ev(pf, 'MISSING', 5, { shipmentId: sf, comment: 'x' })])).status).toBe(201);
      const bad = await receive(f, [ev(pf, 'ACCEPTED', 1, { shipmentId: sf }), ev(pf, 'MISSING', 1, { shipmentId: sf, comment: 'x' })]);
      expect(bad.status).toBe(409);
      const pg = w.position({ quantity: 3 });
      await w.shipment([[pg, 3]]);
      const ok = await receive(f, [ev(pg, 'ACCEPTED', 3), ev(pg, 'RETURNED', 3, { fromAccepted: true, comment: 'x' })]);
      expect(ok.status, JSON.stringify(ok.body)).toBe(201);
      expect(ok.body.positions[0].ledger.acceptedNet).toBe(0);
    } finally {
      await w.cleanup();
    }
  });
});
