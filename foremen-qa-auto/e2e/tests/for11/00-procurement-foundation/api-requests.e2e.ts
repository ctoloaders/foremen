import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { createWorld, destroyWorld, hasMsg, http, isoDate, MSG, newPosition, newRequest, psql, setStatus, type World } from './support.js';

// FOR-11-00 Part A: A7 (purchase requests), A12-03 (hide delivered, requests), A13 (upcoming deliveries for the foreman).
// A8 (overdue sweep) needs the cron enabled on the stack: see FINDINGS.md "not automated".
// Repeatability: run-id generator + teardown in `finally`.
const q = (s: string) => encodeURIComponent(s);
const attach = (w: World, reqId: number, ids: number[], token?: string) =>
  http('POST', `/api/purchase-requests/${reqId}/positions`, token ?? w.tok.manager, { positionIds: ids });
const getReq = async (w: World, id: number) => (await http('GET', `/api/purchase-requests/${id}`, w.tok.manager)).body;
const getPos = async (w: World, id: number) => (await http('GET', `/api/purchase-positions/${id}`, w.tok.manager)).body;
const notifs = (userId: number, type: string) =>
  Number(psql(`SELECT COUNT(*) FROM notifications WHERE recipient_id = :u AND type = :'t';`, { u: String(userId), t: type }));

describe('FOR-11-00 A7 purchase requests', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A7-01 a request is created and positions are attached (positionCount, status OPEN)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p1 = await newPosition(w);
      const p2 = await newPosition(w);
      const req = await newRequest(w, { referenceLink: 'https://example.com/order/1', expectedDeliveryDate: isoDate(7) });
      expect([200, 201]).toContain(req.status);
      expect(req.body.positionCount).toBe(0);
      const r = await attach(w, req.body.id, [p1.body.id, p2.body.id]);
      expect(r.status).toBe(200);
      expect(r.body.positionCount).toBe(2);
      expect(r.body.status).toBe('OPEN');
      expect((await getPos(w, p1.body.id)).purchaseRequestId).toBe(req.body.id);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A7-02 attach restrictions: foreign project, already in another request, cancelled position', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const req = await newRequest(w);
      const other = await newRequest(w);
      const foreign = await newPosition(w, { projectId: w.projB, plannedMaterialId: w.matB }, w.admin);
      const f = await attach(w, req.body.id, [foreign.body.id], w.admin);
      expect([400, 409]).toContain(f.status);
      expect(hasMsg(f, MSG.reqProjectMismatch)).toBe(true);
      const taken = await newPosition(w);
      expect((await attach(w, other.body.id, [taken.body.id])).status).toBe(200);
      const dup = await attach(w, req.body.id, [taken.body.id]);
      expect([400, 409]).toContain(dup.status);
      expect(hasMsg(dup, MSG.alreadyAssigned)).toBe(true);
      const cancelled = await newPosition(w);
      await http('POST', `/api/purchase-positions/${cancelled.body.id}/transition`, w.tok.manager, { target: 'CANCELLED' });
      const c = await attach(w, req.body.id, [cancelled.body.id]);
      expect([400, 409]).toContain(c.status);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A7-03 buyer assignment: only BUYER users, one notification, none on an unchanged save', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const req = await newRequest(w);
      const bad = await http('PUT', `/api/purchase-requests/${req.body.id}`, w.tok.manager, { assignedBuyerId: w.foreman.id });
      expect(bad.status).toBe(400);
      expect(hasMsg(bad, MSG.buyerInvalid)).toBe(true);
      const ok = await http('PUT', `/api/purchase-requests/${req.body.id}`, w.tok.manager, { assignedBuyerId: w.buyer.id });
      expect(ok.status).toBe(200);
      await expect.poll(() => notifs(w!.buyer.id, 'notification.request.assigned'), { timeout: 5000 }).toBe(1);
      expect(psql(`SELECT deep_link FROM notifications WHERE recipient_id = :u AND type = 'notification.request.assigned';`, { u: String(w.buyer.id) }))
        .toBe(`/deliveries?request=${req.body.id}`);
      // saving again with the same buyer does not notify again
      await http('PUT', `/api/purchase-requests/${req.body.id}`, w.tok.manager, { assignedBuyerId: w.buyer.id, notes: 'again' });
      await new Promise((r) => setTimeout(r, 1500));
      expect(notifs(w.buyer.id, 'notification.request.assigned')).toBe(1);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A7-04 derived status and overdue', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const empty = await newRequest(w, { expectedDeliveryDate: isoDate(5) });
      expect(empty.body.status).toBe('OPEN');
      expect(empty.body.overdue).toBe(false);
      const past = await newRequest(w, { expectedDeliveryDate: isoDate(-3) });
      const p = await newPosition(w);
      await attach(w, past.body.id, [p.body.id]);
      const open = await getReq(w, past.body.id);
      expect(open.status).toBe('OPEN');
      expect(open.overdue).toBe(true);
      await http('POST', `/api/purchase-positions/${p.body.id}/transition`, w.tok.manager, { target: 'CANCELLED' });
      const cancelled = await getReq(w, past.body.id);
      expect(cancelled.status).toBe('CANCELLED');
      expect(cancelled.overdue).toBe(false); // CANCELLED requests are never overdue (R11.9)
      const future = await newRequest(w, { expectedDeliveryDate: isoDate(4) });
      const p2 = await newPosition(w);
      await attach(w, future.body.id, [p2.body.id]);
      expect((await getReq(w, future.body.id)).overdue).toBe(false);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A7-05 detach and delete rules', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const req = await newRequest(w);
      const planned = await newPosition(w);
      const pk = await newPosition(w, { sourcingPath: 'PACKAGE' });
      await attach(w, req.body.id, [planned.body.id, pk.body.id]);
      await http('POST', `/api/purchase-positions/${pk.body.id}/transition`, w.tok.manager, { target: 'WITH_BUYER' });
      expect((await http('DELETE', `/api/purchase-requests/${req.body.id}/positions/${planned.body.id}`, w.tok.manager)).status).toBe(200);
      const blocked = await http('DELETE', `/api/purchase-requests/${req.body.id}/positions/${pk.body.id}`, w.tok.manager);
      expect(blocked.status).toBe(409);
      expect(hasMsg(blocked, MSG.detachNotAllowed)).toBe(true);
      const del = await http('DELETE', `/api/purchase-requests/${req.body.id}`, w.tok.manager);
      expect(del.status).toBe(409);
      expect(hasMsg(del, MSG.reqNotEmpty)).toBe(true);
      const empty = await newRequest(w);
      expect((await http('DELETE', `/api/purchase-requests/${empty.body.id}`, w.tok.manager)).status).toBe(204);
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A12 hide delivered (requests)', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A12-03 a fully received request is hidden by hideDelivered; open and empty requests stay', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const done = await newRequest(w);
      const open = await newRequest(w);
      const empty = await newRequest(w);
      const a = await newPosition(w);
      const b = await newPosition(w);
      const c = await newPosition(w);
      await attach(w, done.body.id, [a.body.id]);
      await attach(w, open.body.id, [b.body.id, c.body.id]);
      setStatus(a.body.id, 'RECEIVED');
      setStatus(b.body.id, 'RECEIVED'); // one received, one not: request stays
      const base = `/api/purchase-requests?query=${q(`project.id==${w.projA}`)}&size=100`;
      const ids = (r: any) => r.body.content.map((x: any) => x.id).sort();
      expect(ids(await http('GET', base, w.tok.manager))).toEqual([done.body.id, open.body.id, empty.body.id].sort());
      const hidden = await http('GET', `${base}&hideDelivered=true`, w.tok.manager);
      expect(ids(hidden)).toEqual([open.body.id, empty.body.id].sort());
      expect((await getReq(w, done.body.id)).status).toBe('COMPLETED');
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A13 upcoming deliveries', { tags: ['for-11-00', 'api'] }, () => {
  /** Request +3 days with WITH_BUYER / IN_DELIVERY / PLANNED, request -2 days with ORDERED, one position without a request. */
  async function seed(w: World) {
    const near = await newRequest(w, { expectedDeliveryDate: isoDate(3) });
    const late = await newRequest(w, { expectedDeliveryDate: isoDate(-2) });
    const pw = await newPosition(w);
    const pd = await newPosition(w);
    const pp = await newPosition(w);
    const po = await newPosition(w);
    const free = await newPosition(w);
    await attach(w, near.body.id, [pw.body.id, pd.body.id, pp.body.id]);
    await attach(w, late.body.id, [po.body.id]);
    setStatus(pw.body.id, 'WITH_BUYER');
    setStatus(pd.body.id, 'IN_DELIVERY');
    setStatus(po.body.id, 'ORDERED');
    setStatus(free.body.id, 'WITH_BUYER');
    return { near: near.body.id as number, late: late.body.id as number, pw: pw.body.id as number, pd: pd.body.id as number, pp: pp.body.id as number, po: po.body.id as number, free: free.body.id as number };
  }
  const upcoming = (w: World, qs: string, token?: string) => http('GET', `/api/purchase-positions/upcoming?${qs}`, token ?? w.tok.foreman);

  test('TC-A13-01 composition and order: overdue first, PLANNED and request-less positions excluded, no money fields', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const s = await seed(w);
      const r = await upcoming(w, `days=14&projectId=${w.projA}`);
      expect(r.status).toBe(200);
      const got = r.body.map((x: any) => x.positionId);
      expect(got).toHaveLength(3);
      expect(got[0]).toBe(s.po); // overdue first
      expect(got.slice(1).sort()).toEqual([s.pw, s.pd].sort());
      const first = r.body[0];
      expect(first.overdue).toBe(true);
      expect(first.projectName).toBe(w.nameA);
      expect(first.materialModel).toBe(`QA-MAT-${w.id}-A`);
      expect(first.unitName).toBeTruthy();
      expect(Number(first.quantity)).toBe(3);
      expect(first.requestId).toBe(s.late);
      const keys = Object.keys(first).join(',').toLowerCase();
      expect(keys).not.toMatch(/price|vat|cost|money|amount/);
      expect(r.body.map((x: any) => x.positionId)).not.toContain(s.pp);
      expect(r.body.map((x: any) => x.positionId)).not.toContain(s.free);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A13-02 the days window and its validation', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const s = await seed(w);
      const ids = async (qs: string) => (await upcoming(w!, `${qs}&projectId=${w!.projA}`)).body.map((x: any) => x.positionId);
      const narrow = await ids('days=2');
      expect(narrow).toContain(s.po); // overdue always included
      expect(narrow).not.toContain(s.pw);
      expect(await ids('days=3')).toContain(s.pw);
      for (const bad of ['days=0', 'days=61']) {
        const r = await upcoming(w, bad);
        expect(r.status, bad).toBe(400);
        expect(hasMsg(r, MSG.daysInvalid)).toBe(true);
      }
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A13-03 scope: FOREMAN sees his project only, BUYER sees all, projectId narrows', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const s = await seed(w);
      const reqB = await newRequest(w, { projectId: w.projB, expectedDeliveryDate: isoDate(2) }, w.admin);
      const pb = await newPosition(w, { projectId: w.projB, plannedMaterialId: w.matB }, w.admin);
      await attach(w, reqB.body.id, [pb.body.id], w.admin);
      setStatus(pb.body.id, 'WITH_BUYER');
      const foreman = (await upcoming(w, 'days=30')).body.map((x: any) => x.positionId);
      expect(foreman).toContain(s.pw);
      expect(foreman).not.toContain(pb.body.id);
      const buyer = (await upcoming(w, 'days=30', w.tok.buyer)).body.map((x: any) => x.positionId);
      expect(buyer).toContain(s.pw);
      expect(buyer).toContain(pb.body.id);
      const onlyB = (await upcoming(w, `days=30&projectId=${w.projB}`, w.tok.buyer)).body.map((x: any) => x.positionId);
      expect(onlyB).toContain(pb.body.id);
      expect(onlyB).not.toContain(s.pw);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A13-04 plannedDeliveryDate follows the request date; detach clears it; RECEIVED keeps it', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const req = await newRequest(w, { expectedDeliveryDate: isoDate(5) });
      const live = await newPosition(w);
      const received = await newPosition(w);
      const detached = await newPosition(w);
      await attach(w, req.body.id, [live.body.id, received.body.id, detached.body.id]);
      expect((await getPos(w, live.body.id)).plannedDeliveryDate).toBe(isoDate(5));
      setStatus(received.body.id, 'RECEIVED');
      const moved = isoDate(9);
      const up = await http('PUT', `/api/purchase-requests/${req.body.id}`, w.tok.manager, { expectedDeliveryDate: moved });
      expect(up.status).toBe(200);
      expect((await getPos(w, live.body.id)).plannedDeliveryDate).toBe(moved);
      expect((await getPos(w, received.body.id)).plannedDeliveryDate).toBe(isoDate(5));
      expect((await http('DELETE', `/api/purchase-requests/${req.body.id}/positions/${detached.body.id}`, w.tok.manager)).status).toBe(200);
      expect((await getPos(w, detached.body.id)).plannedDeliveryDate).toBeNull();
      setStatus(live.body.id, 'WITH_BUYER');
      const ids = (await upcoming(w, `days=30&projectId=${w.projA}`)).body.map((x: any) => x.positionId);
      expect(ids).toContain(live.body.id);
      expect(ids).not.toContain(detached.body.id);
    } finally {
      await destroyWorld(w);
    }
  });
});
