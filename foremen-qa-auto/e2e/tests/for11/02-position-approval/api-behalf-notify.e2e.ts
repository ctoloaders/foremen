import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { DELIVERY, Env, INTERNAL_OPTION, dayOffset, http, leaks, psql, waitFor } from './support.js';

// FOR-11-02 Part A9 (on behalf), A10 (withdraw/deadline/overdue), A11 (confidentiality), A12 (notifications),
// A13 (migrations, DB part) and the regression table. Repeatability: generator + teardown (Env, removed in `finally`).
const CL = '/api/position-approvals/client';
const ST = '/api/position-approvals/staff';
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe('FOR-11-02 A9 decision on behalf of the client', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A9-01 the manager records an accept; buyer cannot; no manager notification', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37 });
      await env.send([p1.id]);
      const c = await env.clientPos(p1.id);
      const path = `${ST}/rounds/${c.openRound.roundId}/decide-on-behalf`;
      const body = { decision: 'ACCEPT', optionId: c.openRound.options[0].optionId, expectedUnitSalePrice: 100 };

      // step 3: BUYER is refused (READ-only grant)
      expect((await env.as('buyer', 'POST', path, { ...body, comment: 'x' })).status).toBe(403);
      // step 2: a blank comment is a 400
      expect((await env.as('manager', 'POST', path, { ...body, comment: '' })).status).toBe(400);

      const ok = await env.as('manager', 'POST', path, { ...body, comment: `по телефону, qa-${env.run}` });
      expect(ok.status, ok.text).toBeLessThan(300);
      const s = await env.staffPos(p1.id);
      expect(s.statusCode).toBe('ACCEPTED');
      const closed = s.history.find((h: any) => h.roundId === c.openRound.roundId);
      expect(closed.status).toBe('ACCEPTED');
      expect(closed.decidedSide).toBe('STAFF');
      expect(closed.onBehalf).toBe(true);

      const after = await env.as('client', 'GET', `${CL}/positions/${p1.id}`);
      expect(after.body.history[0].onBehalf).toBe(true);
      expect(after.text).not.toMatch(/decidedBy/i);

      await sleep(1500); // notification emission is asynchronous
      expect(env.notifs('manager').filter((n) => n.type.startsWith('notification.position.'))).toEqual([]);
    } finally {
      await env.cleanup();
    }
  });

  // BUG-3 (FINDINGS.md): a blank comment gives the generic error.validation message instead of error.positionApproval.decision.commentRequired.
  test('TC-A9-01 step 2 / TC-A4-02 a blank comment returns decision.commentRequired', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37 });
      await env.send([p1.id]);
      const c = await env.clientPos(p1.id);
      const key = 'error.positionApproval.decision.commentRequired';
      expect(await env.err('client', 'POST', `${CL}/rounds/${c.openRound.roundId}/reject`, { comment: '' }, 400, key)).toBeNull();
      const body = { decision: 'ACCEPT', optionId: c.openRound.options[0].optionId, expectedUnitSalePrice: 100, comment: '' };
      expect(await env.err('manager', 'POST', `${ST}/rounds/${c.openRound.roundId}/decide-on-behalf`, body, 400, key)).toBeNull();
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A10 withdraw, deadline, overdue', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A10-01 withdraw an open round', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      const roundId = await env.roundId(p1.id);
      const w = await env.as('manager', 'POST', `${ST}/rounds/${roundId}/withdraw`);
      expect(w.status, w.text).toBeLessThan(300);
      const s = await env.staffPos(p1.id);
      expect(s.statusCode).toBe('PLANNED');
      expect(s.decisionDeadline).toBeNull();
      expect(s.history.find((h: any) => h.roundId === roundId).status).toBe('WITHDRAWN');
      const c = await env.clientPos(p1.id);
      expect(c.clientStatus).toBe('NOT_STARTED');
      expect(c.history.length).toBeGreaterThan(0);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A10-02 change the deadline', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      const roundId = await env.roundId(p1.id);
      const ok = await env.as('manager', 'POST', `${ST}/rounds/${roundId}/deadline`, { decisionDeadline: dayOffset(10) });
      expect(ok.status, ok.text).toBeLessThan(300);
      expect((await env.staffPos(p1.id)).openRound.decisionDeadline).toBe(dayOffset(10));
      expect((await env.clientPos(p1.id)).openRound.decisionDeadline).toBe(dayOffset(10));
      expect(await env.err('manager', 'POST', `${ST}/rounds/${roundId}/deadline`, { decisionDeadline: dayOffset(-1) }, 400, 'error.positionApproval.deadline.invalid')).toBeNull();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A10-03 overdue is flagged, nothing changes automatically', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      const roundId = await env.roundId(p1.id);
      psql(`UPDATE position_approval_rounds SET decision_deadline = CURRENT_DATE - 1 WHERE id = :rid;`, { rid: String(roundId) });
      expect((await env.clientPos(p1.id)).overdue).toBe(true);
      const s = await env.staffPos(p1.id);
      expect(s.overdue).toBe(true);
      expect(s.statusCode).toBe('PROPOSED');
      const only = await env.as('manager', 'GET', `${ST}/projects/${env.projectA}?overdue=true`);
      expect(only.body.map((p: any) => p.positionId)).toContain(p1.id);
      const sum = await env.as('client', 'GET', `${CL}/projects/${env.projectA}/summary`);
      expect(sum.body.overdue).toBeGreaterThanOrEqual(1);
      await sleep(1500);
      const types = [...env.notifs('client'), ...env.notifs('manager')].map((n) => n.type);
      expect(types.filter((t) => t.startsWith('notification.position.') && !t.endsWith('decisionRequested'))).toEqual([]);
      expect(types.filter((t) => /overdue/i.test(t))).toEqual([]);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A11 confidentiality', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A11-01 no client endpoint leaks sentinel values', async () => {
    const env = await Env.create();
    try {
      const pa = await env.position('1', { sale: 100, purchase: 61.37, qty: 2, seller: true });
      psql(`UPDATE purchase_positions SET delivery_cost_share = :d WHERE id = :id;`, { d: DELIVERY, id: String(pa.id) });
      const pb = await env.position('2', { sale: 50, purchase: 30 });
      const pc = await env.position('3', { sale: 200, purchase: 150 });
      const pd = await env.position('6', { sale: 80, purchase: 40 });
      const p5 = await env.position('5', { path: 'PACKAGE', sale: null, purchase: null });
      const [m2, m3, m4] = [await env.material(), await env.material(), await env.material()];
      await env.send([pa.id, pb.id, pc.id, pd.id]);
      // analog with the internal option price on pa
      const an = await env.as('manager', 'POST', `${ST}/positions/${pa.id}/propose-analog`, {
        materialId: m2, unitSalePrice: 90, internalUnitPurchasePrice: Number(INTERNAL_OPTION), decisionDeadline: dayOffset(5),
      });
      expect(an.status, an.text).toBe(200);
      await env.as('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: [m3, m4], decisionDeadline: dayOffset(7) });

      const texts: Array<[string, string]> = [];
      const get = async (path: string) => {
        const r = await env.as('client', 'GET', path);
        texts.push([path, r.text]);
        return r;
      };
      await get(`${CL}/projects/${env.projectA}`);
      await get(`${CL}/projects/${env.projectA}?status=AWAITING_DECISION`);
      await get(`${CL}/projects/${env.projectA}/summary`);
      await get(`${CL}/positions/${pa.id}`);

      // decisions: accept pa (analog), reject pb, suggest on pc after accept, client-own on pd
      const pos = await env.clientPos(pa.id);
      const acc = await env.as('client', 'POST', `${CL}/rounds/${pos.openRound.roundId}/accept`, { optionId: pos.openRound.options[0].optionId, expectedUnitSalePrice: 90 });
      texts.push(['accept', acc.text]);
      const rej = await env.as('client', 'POST', `${CL}/rounds/${await env.roundId(pb.id)}/reject`, { comment: 'qa' });
      texts.push(['reject', rej.text]);
      const own = await env.as('client', 'POST', `${CL}/positions/${pd.id}/client-own`, { comment: 'qa' });
      texts.push(['client-own', own.text]);
      await env.accept(pc.id, 200);
      const sug = await env.as('client', 'POST', `${CL}/positions/${pc.id}/suggest`, { link: 'https://shop.example/qa' });
      texts.push(['suggest', sug.text]);
      await get(`${CL}/projects/${env.projectA}`);
      await get(`${CL}/positions/${pa.id}`);

      for (const [what, text] of texts) expect(leaks(text), what).toEqual([]);
      expect((await env.as('client', 'GET', `/api/purchase-positions/${pa.id}/variants`)).status).toBe(403);
      expect((await env.as('client', 'GET', `/api/purchase-positions/${pa.id}/variants/suggestions`)).status).toBe(403);

      // control: the staff view of the same position still shows the internal values
      const staff = await env.as('manager', 'GET', `${ST}/projects/${env.projectA}`);
      expect(staff.text).toContain('SELLER-SENTINEL');
      expect(staff.text).toContain(INTERNAL_OPTION);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A12 notifications', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A12-01/02 send and analog notify the client with the right deep links', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const p2 = await env.position('2', { sale: 50 });
      const m2 = await env.material();
      await env.send([p1.id, p2.id]);
      const sent = await waitFor(async () => env.notifs('client').filter((n) => n.type === 'notification.position.decisionRequested'));
      expect(sent).toBeTruthy();
      await sleep(1000);
      const requested = env.notifs('client').filter((n) => n.type === 'notification.position.decisionRequested');
      expect(requested).toHaveLength(1);
      expect(requested[0].deepLink).toBe(`/projects/${env.projectA}/positionApprovals`);

      await env.as('manager', 'POST', `${ST}/positions/${p1.id}/propose-analog`, {
        materialId: m2, unitSalePrice: 90, decisionDeadline: dayOffset(5),
      });
      const an = await waitFor(async () => env.notifs('client').find((n) => n.type === 'notification.position.analogProposed'));
      expect(an, 'analogProposed').toBeTruthy();
      expect(an!.deepLink).toBe(`/projects/${env.projectA}/positionApprovals?position=${p1.id}`);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A12-03 accept and reject notify the manager, not the client', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const p2 = await env.position('2', { sale: 50 });
      await env.send([p1.id, p2.id]);
      expect((await env.accept(p1.id, 100)).status).toBeLessThan(300);
      await env.as('client', 'POST', `${CL}/rounds/${await env.roundId(p2.id)}/reject`, { comment: 'qa' });
      const types = async () => env.notifs('manager').map((n) => n.type);
      const ok = await waitFor(async () => {
        const t = await types();
        return t.includes('notification.position.accepted') && t.includes('notification.position.rejected');
      });
      expect(ok, JSON.stringify(await types())).toBe(true);
      const own = env.notifs('client').map((n) => n.type);
      expect(own).not.toContain('notification.position.accepted');
      expect(own).not.toContain('notification.position.rejected');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A12-05 client-own notifies manager and buyer', async () => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, block: 3 });
      await env.send([p6.id]);
      const r = await env.as('client', 'POST', `${CL}/positions/${p6.id}/client-own`, { comment: 'qa' });
      expect(r.status, r.text).toBeLessThan(300);
      const has = (role: string) => env.notifs(role).some((n) => n.type === 'notification.position.clientOwn');
      expect(await waitFor(async () => has('manager') && has('buyer'))).toBe(true);
    } finally {
      await env.cleanup();
    }
  });

  test("TC-A12 reading or deleting somebody else's notification is forbidden", async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      await waitFor(async () => env.notifs('client').length > 0);
      const id = Number(psql(`SELECT MAX(id) FROM notifications WHERE recipient_id = :u;`, { u: String(env.users.client.id) }));
      expect((await env.as('manager', 'POST', `/api/notifications/${id}/toggle-read`)).status).toBe(403);
      expect((await env.as('manager', 'DELETE', `/api/notifications/${id}`)).status).toBe(403);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A13 migrations (DB state)', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A13-01 resource, grants and the open-round index exist exactly once', async () => {
    expect(psql(`SELECT COUNT(*) FROM resources WHERE code = 'POSITION_APPROVALS';`)).toBe('1');
    expect(psql(`SELECT COUNT(*) FROM (
        SELECT rr.role_id, rr.resource_id, o.id AS op FROM role_resources rr
        JOIN resources res ON res.id = rr.resource_id AND res.code = 'POSITION_APPROVALS'
        JOIN role_resource_operations rro ON rro.role_resource_id = rr.id JOIN operations o ON o.id = rro.operation_id
        GROUP BY 1, 2, 3 HAVING COUNT(*) > 1) d;`)).toBe('0');
    expect(psql(`SELECT COUNT(*) FROM pg_indexes WHERE indexname = 'uq_par_open_per_position';`)).toBe('1');
  });

  test('TC-A13-01 step 2 two concurrent sends: one 200, one 409', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const t = await env.tok('manager');
      const send = () => http('POST', `${ST}/send`, t, { positionIds: [p1.id], decisionDeadline: dayOffset(7) });
      const [a, b] = await Promise.all([send(), send()]);
      expect([a.status, b.status].sort()).toEqual([200, 409]);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 regression', { tags: ['for-11-02', 'api', 'regression'] }, () => {
  test('R-01 manual transitions accept only MANUAL edges', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const bad = await env.as('manager', 'POST', `/api/purchase-positions/${p1.id}/transition`, { target: 'PROPOSED' });
      expect(bad.status).toBe(409);
      const ok = await env.as('manager', 'POST', `/api/purchase-positions/${p1.id}/transition`, { target: 'CANCELLED' });
      expect(ok.status, ok.text).toBe(200);
    } finally {
      await env.cleanup();
    }
  });

  test('R-02 a manual variant replace is refused in ACCEPTED', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const m2 = await env.material();
      await env.send([p1.id]);
      await env.accept(p1.id, 100);
      const key = 'error.purchasePosition.variant.stageNotAllowed';
      const body = { materialId: m2, salePrice: 90, reason: 'REPRICED' };
      expect(await env.err('manager', 'POST', `/api/purchase-positions/${p1.id}/variants/replace`, body, 409, key)).toBeNull();
    } finally {
      await env.cleanup();
    }
  });

  test('R-03 price visibility: client never sees a PLANNED position, staff roles see prices', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37 });
      expect((await env.as('client', 'GET', `${CL}/positions/${p1.id}`)).status).toBe(404);
      for (const role of ['manager', 'buyer']) {
        const r = await env.as(role, 'GET', `/api/purchase-positions/${p1.id}`);
        expect(r.status, role).toBe(200);
        expect(r.body.salePrice, role).toBe(100);
      }
      const adm = await http('GET', `/api/purchase-positions/${p1.id}`, await (await import('./support.js')).admin());
      expect(adm.body.salePrice).toBe(100);
    } finally {
      await env.cleanup();
    }
  });

  test('R-07 ABAC: CLIENT has no access to purchase resources', async () => {
    const env = await Env.create();
    try {
      for (const p of ['/api/purchase-positions', '/api/purchase-requests', '/api/project-materials']) {
        expect((await env.as('client', 'GET', p)).status, p).toBe(403);
      }
    } finally {
      await env.cleanup();
    }
  });
});
