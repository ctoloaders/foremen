import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  day, eventually, get, makeWorld, notificationsOf, post, psql, put, shipmentBody,
} from './support.js';

// FOR-11-04 Part A (API): A5 upcoming + overlay, A6 notifications, A8 request date vs ETA, regression R-01/R-02.
// Environment: the order-by / downtime sweep cron is "-" (disabled) in this stack, so TC-A6-01 is not
// automated; the shipped / delayed / downtimeRisk notifications are event-driven and are covered.
// Repeatability: generator (run-id) + teardown in `finally` (support.ts makeWorld().cleanup()).

const C1 = 5; // seeded work category ELECTRICAL_ROUGH, read only
const MONEY_KEY = /"[a-zA-Z]*(price|cost|amount|margin|vat)[a-zA-Z]*"\s*:/i;

const fullPut = (s: any, patch: Record<string, unknown> = {}) => ({
  deliveryMethod: s.deliveryMethod, carrierCode: s.carrierCode, trackingNumber: s.trackingNumber,
  trackingUrl: s.trackingUrl, shopOrderLink: s.shopOrderLink, driverName: s.driverName,
  driverPhone: s.driverPhone, note: s.note, ...patch,
});

describe('FOR-11-04 API: upcoming, overlay, notifications', { tags: ['for-11-04', 'api'] }, () => {
  test('TC-A5-01 extended upcoming: shipment fields, shipmentId filter, no money, days=0 is 400', async () => {
    const w = await makeWorld(['BUYER', 'FOREMAN']);
    try {
      const p1 = w.position({ quantity: 10 });
      const p2 = w.position({ quantity: 4 });
      const buyer = w.t('BUYER');
      const s1 = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]], { etaDate: day(2), driverPhone: '+48 600 100 200', driverName: 'Jan' }));
      const s2 = await post('/api/shipments', buyer, shipmentBody(w, [[p2, 2]], { etaDate: day(3) }));
      expect(s1.status, JSON.stringify(s1.body)).toBe(201);
      expect(s2.status).toBe(201);

      const up = await get(`/api/purchase-positions/upcoming?days=14&projectId=${w.projectId}`, w.t('FOREMAN'));
      expect(up.status).toBe(200);
      const row = up.body.find((x: any) => x.positionId === p1);
      expect(row, JSON.stringify(up.body)).toBeTruthy();
      expect(row.shipmentId).toBe(s1.body.id);
      expect(row.shipmentStatus).toBe('CREATED');
      expect(row.etaDate).toBe(day(2));
      expect(row.driverPhone).toBe('+48 600 100 200');
      expect(row.trackingPageUrl).toBeTruthy();
      expect(row.shipmentCount).toBe(1);
      expect(row.plannedDeliveryDate).toBe(day(2));
      expect(JSON.stringify(up.body)).not.toMatch(MONEY_KEY);

      const one = await get(`/api/purchase-positions/upcoming?days=14&projectId=${w.projectId}&shipmentId=${s1.body.id}`, w.t('FOREMAN'));
      expect(one.body.map((x: any) => x.positionId)).toEqual([p1]);

      const zero = await get('/api/purchase-positions/upcoming?days=0', w.t('FOREMAN'));
      expect(zero.status).toBe(400);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A5-02 project isolation: a foreman of another project sees nothing of this one', async () => {
    const w = await makeWorld(['BUYER', 'FOREMAN', 'FOREMAN2']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 2]], { etaDate: day(2) }));
      expect(s.status).toBe(201);

      const all = await get('/api/purchase-positions/upcoming?days=14', w.t('FOREMAN2'));
      expect(all.status).toBe(200);
      expect(all.body.filter((x: any) => x.projectId === w.projectId)).toEqual([]);
      const scoped = await get(`/api/purchase-positions/upcoming?days=14&projectId=${w.projectId}`, w.t('FOREMAN2'));
      expect(scoped.body).toEqual([]);
      expect((await get(`/api/shipments/${s.body.id}`, w.t('FOREMAN2'))).status).toBe(404);
      expect((await get(`/api/purchase-positions/schedule-overlay?projectId=${w.projectId}`, w.t('FOREMAN2'))).status).toBe(404);
      // the member foreman does see it
      const own = await get(`/api/purchase-positions/upcoming?days=14&projectId=${w.projectId}`, w.t('FOREMAN'));
      expect(own.body.map((x: any) => x.positionId)).toContain(p1);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A5-03 overlay: works start, downtimeRisk follows the ETA, orderByMissed, no money, empty for no schedule', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      w.schedule(C1, 5); // project start = today, bar starts on day 5 => works start = today + 4
      const p1 = w.position({ quantity: 10, workCategoryId: C1, orderBy: day(-1) });
      const p4 = w.position({ status: 'WITH_BUYER', quantity: 3, workCategoryId: C1, orderBy: day(-1) });
      const buyer = w.t('BUYER');
      const s = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]], { etaDate: day(6) })); // project start + 6 (works start is + 4)
      expect(s.status, JSON.stringify(s.body)).toBe(201);

      const overlay = async () => {
        const r = await get(`/api/purchase-positions/schedule-overlay?projectId=${w.projectId}`, w.t('MANAGER'));
        expect(r.status, JSON.stringify(r.body)).toBe(200);
        expect(JSON.stringify(r.body)).not.toMatch(MONEY_KEY);
        const cat = r.body.categories.find((c: any) => c.workCategoryId === C1);
        expect(cat, JSON.stringify(r.body)).toBeTruthy();
        return { cat, byId: (id: number) => cat.positions.find((p: any) => p.positionId === id) };
      };

      let o = await overlay();
      expect(o.cat.worksStartDate).toBe(day(4));
      expect(o.byId(p1).downtimeRisk).toBe(true);
      expect(o.byId(p1).arrivalDate).toBe(day(6));
      expect(o.byId(p1).orderByMissed).toBe(false); // already past ORDERED
      expect(o.byId(p4).orderByMissed).toBe(true);

      const fixed = await put(`/api/shipments/${s.body.id}`, buyer, fullPut(s.body, { etaDate: day(2) })); // project start + 2, before the works
      expect(fixed.status).toBe(200);
      o = await overlay();
      expect(o.byId(p1).downtimeRisk).toBe(false);

      // a project without a schedule: 200 with empty categories
      const none = await get(`/api/purchase-positions/schedule-overlay?projectId=${w.otherProjectId}`, w.t('MANAGER'));
      expect(none.status).toBe(200);
      expect(none.body.categories).toEqual([]);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A6-02 shipped / delayed / downtimeRisk notifications: recipients, links, one per ETA, none for the actor', async () => {
    const w = await makeWorld(['BUYER', 'FOREMAN']);
    try {
      w.schedule(C1, 5); // works start = today + 4
      const p1 = w.position({ quantity: 10, workCategoryId: C1 });
      const buyer = w.t('BUYER');
      const s = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]]));
      expect(s.status, JSON.stringify(s.body)).toBe(201);
      const id = s.body.id;
      const of = async (tag: 'MANAGER' | 'FOREMAN' | 'BUYER', type: string) =>
        (await notificationsOf(w.t(tag))).filter((n) => n.type === type);

      const shippedM = await eventually(async () => {
        const l = await of('MANAGER', 'notification.shipment.shipped');
        return l.length ? l : false;
      });
      expect(shippedM?.[0]?.deepLink).toBe(`/deliveries/shipments?shipment=${id}`);
      const shippedF = await eventually(async () => {
        const l = await of('FOREMAN', 'notification.shipment.shipped');
        return l.length ? l : false;
      });
      expect(shippedF?.[0]?.deepLink).toBe(`/deliveries/upcoming?shipment=${id}`);
      expect(await of('BUYER', 'notification.shipment.shipped')).toEqual([]);

      let cur = s.body;
      const setEta = async (tag: 'BUYER' | 'MANAGER', date: string) => {
        const r = await put(`/api/shipments/${id}`, w.t(tag), fullPut(cur, { etaDate: date }));
        expect(r.status, JSON.stringify(r.body)).toBe(200);
        cur = r.body;
      };

      await setEta('BUYER', day(2)); // first ETA, before the works start: nothing
      await setEta('BUYER', day(6)); // +4 days later, after works start: delayed + downtime risk
      const delayed1 = await eventually(async () => {
        const l = await of('MANAGER', 'notification.shipment.delayed');
        return l.length >= 1 ? l : false;
      });
      expect(delayed1).toHaveLength(1);
      const risk1 = await eventually(async () => {
        const l = await of('MANAGER', 'notification.shipment.downtimeRisk');
        return l.length >= 1 ? l : false;
      });
      expect(risk1).toHaveLength(1);

      await setEta('BUYER', day(8)); // later again: another of each (distinct ETA)
      const delayed2 = await eventually(async () => {
        const l = await of('MANAGER', 'notification.shipment.delayed');
        return l.length >= 2 ? l : false;
      });
      expect(delayed2).toHaveLength(2);
      const risk2 = await eventually(async () => {
        const l = await of('MANAGER', 'notification.shipment.downtimeRisk');
        return l.length >= 2 ? l : false;
      });
      expect(risk2).toHaveLength(2);

      // same date again: nothing new (give the listeners a moment, then count)
      await setEta('BUYER', day(8));
      await new Promise((r) => setTimeout(r, 1500));
      expect(await of('MANAGER', 'notification.shipment.delayed')).toHaveLength(2);
      expect(await of('MANAGER', 'notification.shipment.downtimeRisk')).toHaveLength(2);
      expect((await of('FOREMAN', 'notification.shipment.delayed')).every((n) => n.deepLink === `/deliveries/upcoming?shipment=${id}`)).toBe(true);
      expect(await of('FOREMAN', 'notification.shipment.delayed')).toHaveLength(2);

      // the actor gets nothing: a MANAGER edit notifies the foreman, not the manager
      await setEta('MANAGER', day(9));
      const foremanDelayed = await eventually(async () => {
        const l = await of('FOREMAN', 'notification.shipment.delayed');
        return l.length >= 3 ? l : false;
      });
      expect(foremanDelayed).toHaveLength(3);
      expect(await of('MANAGER', 'notification.shipment.delayed')).toHaveLength(2);
      expect(await of('BUYER', 'notification.shipment.delayed')).toEqual([]);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-A8-01 / TC-R-02 request date does not overwrite an active shipment ETA; without a shipment it applies', async () => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const p2 = w.position({ quantity: 4 });
      const requestId = Number(
        psql(
          `INSERT INTO purchase_requests (project_id, reference_number, expected_delivery_date, created_by)
           VALUES (:'pid'::bigint, :'ref', :'d'::date, 'qa') RETURNING id;`,
          { pid: String(w.projectId), ref: `FOR1104-${w.id}-REQ`, d: day(7) },
        ).split('\n')[0],
      );
      psql(`UPDATE purchase_positions SET purchase_request_id = :rid, planned_delivery_date = :'d'::date WHERE id IN (:p1, :p2);`, {
        rid: String(requestId), d: day(7), p1: String(p1), p2: String(p2),
      });

      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 4]], { etaDate: day(3) }));
      expect(s.status, JSON.stringify(s.body)).toBe(201);
      expect(w.positionRow(p1).planned).toBe(day(3));

      const upd = await put(`/api/purchase-requests/${requestId}`, w.t('ADMIN'), { expectedDeliveryDate: day(12) });
      expect(upd.status, JSON.stringify(upd.body)).toBe(200);
      expect(w.positionRow(p1).planned).toBe(day(3)); // shipment ETA wins
      expect(w.positionRow(p2).planned).toBe(day(12)); // no shipment: request date applies (R-02)

      // cancelling the shipment hands the position back to the request date
      expect((await post(`/api/shipments/${s.body.id}/cancel`, w.t('BUYER'))).status).toBe(200);
      expect(w.positionRow(p1).planned).toBe(day(12));
    } finally {
      await w.cleanup();
    }
  });

  test('TC-R-01 upcoming for a position without shipments keeps its fields and returns null shipment fields', async () => {
    const w = await makeWorld(['FOREMAN']);
    try {
      const p = w.position({ quantity: 4 });
      psql(`UPDATE purchase_positions SET planned_delivery_date = :'d'::date WHERE id = :p;`, { d: day(3), p: String(p) });
      const up = await get(`/api/purchase-positions/upcoming?days=14&projectId=${w.projectId}`, w.t('FOREMAN'));
      expect(up.status).toBe(200);
      const row = up.body.find((x: any) => x.positionId === p);
      expect(row).toBeTruthy();
      for (const k of ['positionId', 'projectId', 'projectName', 'materialModel', 'quantity', 'unitName', 'plannedDeliveryDate', 'status', 'overdue']) {
        expect(row, k).toHaveProperty(k);
      }
      expect(row.plannedDeliveryDate).toBe(day(3));
      for (const k of ['shipmentId', 'shipmentStatus', 'etaDate', 'driverPhone', 'trackingPageUrl', 'shipmentCount']) {
        expect(row[k] ?? null, k).toBeNull();
      }
    } finally {
      await w.cleanup();
    }
  });
});
