import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { day, get, makeWorld, msgOf, MSG, post, put, shipmentBody, type World } from './support.js';

// FOR-11-04 Part A (API): A1 create, A2 manual entry, A3 cancel, A7 roles, A4 (refresh without a tracker).
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-04-shipment-tracking-and-schedule/test-cases.md.
// Environment: the Docker stack runs with FOREMEN_PROCUREMENT_TRACKING_ENABLED=false and
// FOREMEN_TRACKING_STUB_ENABLED=false, so carrier STUB does not exist; carrier DPD (link-out, never
// fetched) stands in for it and every STUB-driven step is "not automated" (see FINDINGS.md).
// Repeatability: generator (run-id in users/projects) + teardown of every row in `finally`.

/** Full PUT body from a shipment DTO (PUT replaces method/driver/note fields, see BUG-2). */
const fullPut = (s: any, patch: Record<string, unknown> = {}) => ({
  deliveryMethod: s.deliveryMethod,
  carrierCode: s.carrierCode,
  trackingNumber: s.trackingNumber,
  trackingUrl: s.trackingUrl,
  shopOrderLink: s.shopOrderLink,
  driverName: s.driverName,
  driverPhone: s.driverPhone,
  note: s.note,
  ...patch,
});

describe('FOR-11-04 API: shipments', { tags: ['for-11-04', 'api'] }, () => {
  test('TC-A1-01 BUYER creates a carrier shipment: CREATED, positions go IN_DELIVERY (STUB tracking steps not automated)', async () => {
    const w = await makeWorld(['BUYER', 'FOREMAN']);
    try {
      const p1 = w.position({ quantity: 10 });
      const p2 = w.position({ quantity: 4 });
      const eo1 = w.order([[p1, 6], [p2, 4]]);

      const r = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 4], [p2, 4]], { externalOrderId: eo1 }));
      expect(r.status, JSON.stringify(r.body)).toBe(201);
      expect(r.body.status).toBe('CREATED');
      expect(r.body.etaDate ?? null).toBeNull();
      expect(r.body.events).toHaveLength(1);
      expect(r.body.lines).toHaveLength(2);
      expect(r.body.trackingPageUrl).toContain('dpd');
      expect(w.positionRow(p1).status).toBe('IN_DELIVERY');
      expect(w.positionRow(p2).status).toBe('IN_DELIVERY');
      // the response carries no money field
      expect(JSON.stringify(r.body)).not.toMatch(/"[a-zA-Z]*(price|cost|amount|margin|vat)[a-zA-Z]*"/i);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-02 shop delivery and phone contact: foreign-method fields are null, validation of phone', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p3 = w.position({ path: 'CLIENT_OWN', status: 'IN_DELIVERY', quantity: 2 });
      const buyer = w.t('BUYER');

      const shop = await post('/api/shipments', buyer, {
        projectId: w.projectId, deliveryMethod: 'SHOP_DELIVERY', shopOrderLink: 'https://shop.example.com/o/1',
        driverPhone: '+48 600 100 200', carrierCode: 'DPD', trackingNumber: 'DPD123456',
        lines: [{ positionId: p3, quantity: 1 }],
      });
      expect(shop.status, JSON.stringify(shop.body)).toBe(201);
      expect(shop.body.carrierCode ?? null).toBeNull();
      expect(shop.body.trackingNumber ?? null).toBeNull();
      expect(shop.body.shopOrderLink).toBe('https://shop.example.com/o/1');

      const noPhone = await post('/api/shipments', buyer, {
        projectId: w.projectId, deliveryMethod: 'PHONE_CONTACT', lines: [{ positionId: p3, quantity: 1 }],
      });
      expect(noPhone.status).toBe(400);
      // test-cases.md names error.shipment.method.fieldsMissing; the backend answers error.shipment.phone.required
      expect(msgOf(noPhone)).toBe(MSG.phoneRequired);

      const phone = await post('/api/shipments', buyer, {
        projectId: w.projectId, deliveryMethod: 'PHONE_CONTACT', driverPhone: '+48 600 100 200', etaDate: day(1),
        lines: [{ positionId: p3, quantity: 1 }],
      });
      expect(phone.status, JSON.stringify(phone.body)).toBe(201);
      expect(phone.body.etaSource).toBe('MANUAL');
      expect(phone.body.etaDate).toBe(day(1));
      expect(w.positionRow(p3).status).toBe('IN_DELIVERY');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A1-03 line validation: order line, cap, duplicate, empty, stage, project, number, carrier, link', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const p2 = w.position({ quantity: 4 });
      const pOrdered = w.position({ status: 'ORDERED', quantity: 3 });
      const pOther = w.position({ projectId: w.otherProjectId, quantity: 3 });
      const eo1 = w.order([[p1, 6], [p2, 4]]);
      const buyer = w.t('BUYER');
      const base = (lines: Array<[number, number]>, extra: Record<string, unknown> = {}) => shipmentBody(w, lines, extra);

      const first = await post('/api/shipments', buyer, base([[p1, 4]], { externalOrderId: eo1 }));
      expect(first.status, JSON.stringify(first.body)).toBe(201);

      // 1) 4 already on the order, 3 more > order line 6 (test-cases.md says 7, but 4+7=11 trips the cap
      // check first, which runs before the order-line check; 3 isolates the order-line rule).
      // Requirements R16.5: stage/quantity conflicts are 409.
      const overOrder = await post('/api/shipments', buyer, base([[p1, 3]], { externalOrderId: eo1 }));
      expect(overOrder.status).toBe(409);
      expect(msgOf(overOrder)).toBe(MSG.exceedsOrderLine);

      // 2) without an order: 4 + 6 = 10 = cap passes, one more does not
      const upToCap = await post('/api/shipments', buyer, base([[p1, 6]]));
      expect(upToCap.status, JSON.stringify(upToCap.body)).toBe(201);
      const overCap = await post('/api/shipments', buyer, base([[p1, 1]]));
      expect(overCap.status).toBe(409);
      expect(msgOf(overCap)).toBe(MSG.exceedsCap);

      // 3) duplicate and empty lines
      const dup = await post('/api/shipments', buyer, base([[p2, 1], [p2, 1]]));
      expect(dup.status).toBe(400);
      expect(msgOf(dup)).toBe(MSG.duplicate);
      const empty = await post('/api/shipments', buyer, base([]));
      expect(empty.status).toBe(400);
      expect(msgOf(empty)).toBe(MSG.empty);

      // 4) a position that is not paid yet
      const notShippable = await post('/api/shipments', buyer, base([[pOrdered, 1]]));
      expect(notShippable.status).toBe(409);
      expect(msgOf(notShippable)).toBe(MSG.notShippable);

      // 5) a position of another project
      const mismatch = await post('/api/shipments', buyer, base([[pOther, 1]]));
      expect(mismatch.status).toBe(400);
      expect(msgOf(mismatch)).toBe(MSG.projectMismatch);

      // 6) tracking number and carrier
      const badNumber = await post('/api/shipments', buyer, base([[p2, 1]], { trackingNumber: 'ab' }));
      expect(badNumber.status).toBe(400);
      expect(msgOf(badNumber)).toBe(MSG.trackingInvalid);
      const badCarrier = await post('/api/shipments', buyer, base([[p2, 1]], { carrierCode: 'XYZ' }));
      expect(badCarrier.status).toBe(400);
      expect(msgOf(badCarrier)).toBe(MSG.carrierUnknown);

      // 7) link scheme
      const badLink = await post('/api/shipments', buyer, {
        projectId: w.projectId, deliveryMethod: 'SHOP_DELIVERY', shopOrderLink: 'ftp://x', lines: [{ positionId: p2, quantity: 1 }],
      });
      expect(badLink.status).toBe(400);
      expect(msgOf(badLink)).toBe(MSG.linkInvalid);

      // nothing else was created and no stage moved
      const list = await get(`/api/shipments?projectId=${w.projectId}&size=50`, buyer);
      expect(list.body.totalElements).toBe(2);
      expect(w.positionRow(pOrdered).status).toBe('ORDERED');
      expect(w.positionRow(p2).status).toBe('SUPPLIER_PAID');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A2-01 (partial) manual ETA is stored as MANUAL and fed to plannedDeliveryDate; revert needs an enabled tracker', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const buyer = w.t('BUYER');
      const created = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]]));
      expect(created.status).toBe(201);
      const id = created.body.id;

      const upd = await put(`/api/shipments/${id}`, buyer, fullPut(created.body, { etaDate: day(5), etaTimeFrom: '09:00', etaTimeTo: '12:00' }));
      expect(upd.status, JSON.stringify(upd.body)).toBe(200);
      expect(upd.body.etaSource).toBe('MANUAL');
      expect(upd.body.etaDate).toBe(day(5));
      expect(String(upd.body.etaTimeFrom)).toContain('09:00');
      expect(w.positionRow(p1).planned).toBe(day(5));

      // Tracking is off in this stack (and DPD is link-out): revert/refresh are "not available" (R10.1).
      const revert = await post(`/api/shipments/${id}/eta/revert`, buyer);
      expect(revert.status).toBe(409);
      expect(msgOf(revert)).toBe(MSG.revertNotAvailable);
      const refresh = await post(`/api/shipments/${id}/refresh`, buyer);
      expect(refresh.status).toBe(409);
      expect(msgOf(refresh)).toBe(MSG.refreshNotAvailable);
    } finally {
      await w.cleanup();
    }
  });

  // BUG-2 (FINDINGS.md): PUT is not a partial update; the sparse body of test-cases.md is rejected (CARRIER) or wipes fields (other methods)
  test('TC-A2-01 step 1 / TC-A2-02 step 1 a sparse PUT {etaDate} or {status} changes only that field', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const buyer = w.t('BUYER');
      const created = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]], { driverName: 'Jan', note: 'keep me' }));
      expect(created.status).toBe(201);
      const sparse = await put(`/api/shipments/${created.body.id}`, buyer, { etaDate: day(5), etaTimeFrom: '09:00', etaTimeTo: '12:00' });
      expect(sparse.status, JSON.stringify(sparse.body)).toBe(200);
      expect(sparse.body.carrierCode).toBe('DPD');
      expect(sparse.body.driverName).toBe('Jan');
      expect(sparse.body.note).toBe('keep me');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A2-02 manual status, DELIVERED does not move positions, a terminal shipment accepts only a note', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const buyer = w.t('BUYER');
      const created = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]]));
      const id = created.body.id;

      const out = await put(`/api/shipments/${id}`, buyer, fullPut(created.body, { status: 'OUT_FOR_DELIVERY' }));
      expect(out.status, JSON.stringify(out.body)).toBe(200);
      expect(out.body.status).toBe('OUT_FOR_DELIVERY');
      expect(out.body.statusSource).toBe('MANUAL');
      expect(out.body.events.some((e: any) => e.source === 'MANUAL' && e.status === 'OUT_FOR_DELIVERY')).toBe(true);

      const done = await put(`/api/shipments/${id}`, buyer, fullPut(out.body, { status: 'DELIVERED' }));
      expect(done.status).toBe(200);
      expect(done.body.status).toBe('DELIVERED');
      expect(done.body.deliveredAt).toBeTruthy();
      expect(w.positionRow(p1).status).toBe('IN_DELIVERY');

      const eta = await put(`/api/shipments/${id}`, buyer, fullPut(done.body, { etaDate: day(3) }));
      expect(eta.status).toBe(409);
      expect(msgOf(eta)).toBe(MSG.terminal);
      const status = await put(`/api/shipments/${id}`, buyer, fullPut(done.body, { status: 'IN_TRANSIT' }));
      expect(status.status).toBe(409);

      const note = await put(`/api/shipments/${id}`, buyer, fullPut(done.body, { note: 'closed note' }));
      expect(note.status, JSON.stringify(note.body)).toBe(200);
      expect(note.body.note).toBe('closed note');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A2-02 (API side of BUG-3) a manual status outside CREATED/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION is a 400', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const buyer = w.t('BUYER');
      const created = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 2]]));
      for (const status of ['UNKNOWN', 'CANCELLED']) {
        const r = await put(`/api/shipments/${created.body.id}`, buyer, fullPut(created.body, { status }));
        expect(r.status, status).toBe(400);
      }
      expect((await get(`/api/shipments/${created.body.id}`, buyer)).body.status).toBe('CREATED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A3-01 cancel returns positions to SUPPLIER_PAID only without other active shipments; CLIENT_OWN stays', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const p2 = w.position({ quantity: 4 });
      const p3 = w.position({ path: 'CLIENT_OWN', status: 'IN_DELIVERY', quantity: 2 });
      const buyer = w.t('BUYER');

      // 1-3: only P2
      const s = await post('/api/shipments', buyer, shipmentBody(w, [[p2, 4]]));
      expect(s.status).toBe(201);
      expect(w.positionRow(p2).status).toBe('IN_DELIVERY');
      const c = await post(`/api/shipments/${s.body.id}/cancel`, buyer);
      expect(c.status, JSON.stringify(c.body)).toBe(200);
      expect(c.body.status).toBe('CANCELLED');
      expect(w.positionRow(p2)).toEqual({ status: 'SUPPLIER_PAID', planned: null });

      // 4: P1 on two shipments; cancelling one keeps IN_DELIVERY, cancelling the other reverses
      const a = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 3]]));
      const b = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 3]]));
      expect(a.status).toBe(201);
      expect(b.status).toBe(201);
      expect((await post(`/api/shipments/${a.body.id}/cancel`, buyer)).status).toBe(200);
      expect(w.positionRow(p1).status).toBe('IN_DELIVERY');
      expect((await post(`/api/shipments/${b.body.id}/cancel`, buyer)).status).toBe(200);
      expect(w.positionRow(p1).status).toBe('SUPPLIER_PAID');

      // 5: CLIENT_OWN stays IN_DELIVERY
      const own = await post('/api/shipments', buyer, shipmentBody(w, [[p3, 1]]));
      expect(own.status, JSON.stringify(own.body)).toBe(201);
      expect((await post(`/api/shipments/${own.body.id}/cancel`, buyer)).status).toBe(200);
      expect(w.positionRow(p3).status).toBe('IN_DELIVERY');

      // 6: repeat cancel
      const again = await post(`/api/shipments/${s.body.id}/cancel`, buyer);
      expect(again.status).toBe(409);
      expect(msgOf(again)).toBe(MSG.terminal);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A4-02/03 (partial) refresh of a shipment without an enabled tracker is a 409, never a 5xx; manual ETA still works', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const buyer = w.t('BUYER');
      const s = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 2]]));
      for (let i = 0; i < 2; i += 1) {
        const r = await post(`/api/shipments/${s.body.id}/refresh`, buyer);
        expect(r.status).toBe(409);
      }
      const manual = await put(`/api/shipments/${s.body.id}`, buyer, fullPut(s.body, { etaDate: day(2) }));
      expect(manual.status).toBe(200);
      expect(manual.body.degraded).toBe(false);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A7-01 role matrix: list/get, create, update, cancel, refresh; FOREMAN gets note=null; foreign foreman gets 404', async () => {
    const w = await makeWorld(['BUYER', 'FOREMAN', 'FOREMAN2', 'FINANCIER']);
    try {
      const p = w.position({ quantity: 10 });
      const shopBody = (extra: Record<string, unknown> = {}) => ({
        projectId: w.projectId, deliveryMethod: 'PHONE_CONTACT', driverPhone: '+48 600 100 200', driverName: 'Jan',
        note: 'internal buyer note', lines: [{ positionId: p, quantity: 1 }], ...extra,
      });

      // create: ADMIN and BUYER 201, others 403
      const byAdmin = await post('/api/shipments', w.t('ADMIN'), shopBody());
      expect(byAdmin.status, JSON.stringify(byAdmin.body)).toBe(201);
      const byBuyer = await post('/api/shipments', w.t('BUYER'), shopBody());
      expect(byBuyer.status).toBe(201);
      for (const tag of ['MANAGER', 'FOREMAN', 'FINANCIER', 'CLIENT'] as const) {
        const r = await post('/api/shipments', w.t(tag), shopBody());
        expect(r.status, `create as ${tag}`).toBe(403);
      }

      // list / get
      const id = byAdmin.body.id;
      for (const tag of ['ADMIN', 'BUYER', 'MANAGER', 'FOREMAN', 'FINANCIER'] as const) {
        const list = await get(`/api/shipments?projectId=${w.projectId}`, w.t(tag));
        expect(list.status, `list as ${tag}`).toBe(200);
        expect(list.body.totalElements, `list as ${tag}`).toBe(2);
        const one = await get(`/api/shipments/${id}`, w.t(tag));
        expect(one.status, `get as ${tag}`).toBe(200);
        expect(one.body.driverPhone, `phone as ${tag}`).toBe('+48 600 100 200');
        expect(one.body.note, `note as ${tag}`).toBe(tag === 'FOREMAN' ? null : 'internal buyer note');
      }
      expect((await get(`/api/shipments/${id}`, w.t('CLIENT'))).status).toBe(403);
      expect((await get(`/api/shipments?projectId=${w.projectId}`, w.t('CLIENT'))).status).toBe(403);
      // a foreman of another project: no existence leak
      expect((await get(`/api/shipments/${id}`, w.t('FOREMAN2'))).status).toBe(404);

      // update / refresh: allowed ADMIN, BUYER, MANAGER (not 403/404); FOREMAN, FINANCIER, CLIENT 403
      const body = fullPut(byAdmin.body, { note: 'x' });
      for (const tag of ['FOREMAN', 'FINANCIER', 'CLIENT'] as const) {
        expect((await put(`/api/shipments/${id}`, w.t(tag), body)).status, `update as ${tag}`).toBe(403);
        expect((await post(`/api/shipments/${id}/cancel`, w.t(tag))).status, `cancel as ${tag}`).toBe(403);
        expect((await post(`/api/shipments/${id}/refresh`, w.t(tag))).status, `refresh as ${tag}`).toBe(403);
      }
      for (const tag of ['ADMIN', 'BUYER', 'MANAGER'] as const) {
        expect((await put(`/api/shipments/${id}`, w.t(tag), body)).status, `update as ${tag}`).toBe(200);
        // not a permission failure (409 = no enabled tracker for a non-CARRIER shipment)
        expect([200, 409], `refresh as ${tag}`).toContain((await post(`/api/shipments/${id}/refresh`, w.t(tag))).status);
      }
      // cancel: MANAGER cancels the BUYER's shipment, BUYER cancels the admin's one
      expect((await post(`/api/shipments/${byBuyer.body.id}/cancel`, w.t('MANAGER'))).status).toBe(200);
      expect((await post(`/api/shipments/${id}/cancel`, w.t('BUYER'))).status).toBe(200);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-03 manual position transition is unchanged: no manual edge IN_DELIVERY -> SUPPLIER_PAID', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p, 2]]));
      expect(s.status).toBe(201);
      // IN_DELIVERY -> SUPPLIER_PAID is SYSTEM only (R6.2); a manual transition must be refused
      const r = await post(`/api/purchase-positions/${p}/transition`, w.t('ADMIN'), { target: 'SUPPLIER_PAID' });
      expect([400, 409, 422]).toContain(r.status);
      expect(w.positionRow(p).status).toBe('IN_DELIVERY');
    } finally {
      await w.cleanup();
    }
  });
});

// keep the type import used for helpers that take a world
export type { World };
