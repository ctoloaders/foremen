import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { Env, dayOffset, leaks, psql } from './support.js';

// FOR-11-02 Part A2 (send), A3 (accept), A4 (reject). Source: FOR-11-02 test-cases.md.
// Repeatability: generator + teardown (Env: run-id users/project/materials/positions, removed in `finally`).
const CL = '/api/position-approvals/client';
const ST = '/api/position-approvals/staff';

describe('FOR-11-02 A2 send for approval', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A2-01 sending a block, client view and confidentiality', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, qty: 2, seller: true });
      const p2 = await env.position('2', { sale: 50, purchase: 30, qty: 1 });
      const send = await env.send([p1.id, p2.id], 7);
      expect(send.status, send.text).toBe(200);

      const s1 = await env.staffPos(p1.id);
      const s2 = await env.staffPos(p2.id);
      for (const s of [s1, s2]) {
        expect(s.statusCode).toBe('PROPOSED');
        expect(s.openRound.kind).toBe('PROPOSAL');
        expect(s.openRound.status).toBe('OPEN');
        expect(s.openRound.decisionDeadline).toBe(dayOffset(7));
      }

      const list = await env.as('client', 'GET', `${CL}/projects/${env.projectA}`);
      expect(list.status).toBe(200);
      const c1 = list.body.find((p: any) => p.positionId === p1.id);
      const c2 = list.body.find((p: any) => p.positionId === p2.id);
      expect(c1.clientStatus).toBe('AWAITING_DECISION');
      expect(c2.clientStatus).toBe('AWAITING_DECISION');
      expect(c1.money).toMatchObject({ unitNet: 100, vatRatePercent: 8, unitGross: 108, lineGross: 216 });
      expect(c2.money).toMatchObject({ unitNet: 50, vatRatePercent: 8, unitGross: 54, lineGross: 54 });
      expect(leaks(list.text)).toEqual([]);
      // the staff view keeps the internal values (control: masking did not blank staff data)
      expect(s1.internalUnitPurchasePrice).toBe(61.37);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A2-02 a position without a sale price cannot be sent', async () => {
    const env = await Env.create();
    try {
      const p4 = await env.position('4', { sale: null });
      expect(await env.err('manager', 'POST', `${ST}/send`, { positionIds: [p4.id], decisionDeadline: dayOffset(7) }, 409, 'error.positionApproval.send.priceMissing')).toBeNull();
      const row = psql(`SELECT status FROM purchase_positions WHERE id = :id;`, { id: String(p4.id) });
      expect(row).toBe('PLANNED');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A2-03 a deadline in the past is rejected', async () => {
    const env = await Env.create();
    try {
      const p3 = await env.position('3', { sale: 200, purchase: 150, qty: 3, block: 2 });
      expect(await env.err('manager', 'POST', `${ST}/send`, { positionIds: [p3.id], decisionDeadline: dayOffset(-1) }, 400, 'error.positionApproval.deadline.invalid')).toBeNull();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A2-04 sending is atomic', async () => {
    const env = await Env.create();
    try {
      const p3 = await env.position('3', { sale: 200, purchase: 150, qty: 3, block: 2 });
      const p4 = await env.position('4', { sale: null });
      const r = await env.as('manager', 'POST', `${ST}/send`, { positionIds: [p3.id, p4.id], decisionDeadline: dayOffset(7) });
      expect(r.status).toBe(409);
      expect(psql(`SELECT status FROM purchase_positions WHERE id = :id;`, { id: String(p3.id) })).toBe('PLANNED');
      expect(psql(`SELECT COUNT(*) FROM position_approval_rounds WHERE position_id IN (${p3.id}, ${p4.id});`)).toBe('0');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A2-05 a repeated send is a conflict', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      expect((await env.send([p1.id])).status).toBe(200);
      expect(await env.err('manager', 'POST', `${ST}/send`, { positionIds: [p1.id], decisionDeadline: dayOffset(7) }, 409, 'error.positionApproval.send.notEligible')).toBeNull();
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A3 client accepts', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A3-01 successful accept', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, qty: 2 });
      await env.send([p1.id]);
      const roundId = await env.roundId(p1.id);
      const r = await env.accept(p1.id, 100);
      expect(r.status, r.text).toBeLessThan(300);
      const s = await env.staffPos(p1.id);
      expect(s.statusCode).toBe('ACCEPTED');
      expect(s.decisionDeadline).toBeNull();
      const closed = s.history.find((h: any) => h.roundId === roundId);
      expect(closed.status).toBe('ACCEPTED');
      expect(closed.decidedSide).toBe('CLIENT');
      const c = await env.clientPos(p1.id);
      expect(c.clientStatus).toBe('ACCEPTED');
      expect(c.money.unitGross).toBe(108);
      expect(c.history[0].status).toBe('ACCEPTED');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A3-02 a price mismatch is rejected', async () => {
    const env = await Env.create();
    try {
      const p2 = await env.position('2', { sale: 50, purchase: 30 });
      await env.send([p2.id]);
      const p = await env.clientPos(p2.id);
      const path = `${CL}/rounds/${p.openRound.roundId}/accept`;
      const body = { optionId: p.openRound.options[0].optionId, expectedUnitSalePrice: 49 };
      expect(await env.err('client', 'POST', path, body, 409, 'error.positionApproval.priceChanged')).toBeNull();
      expect((await env.clientPos(p2.id)).openRound.status).toBe('OPEN');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A3-03 a stale offer is rejected after the price changed', async () => {
    const env = await Env.create();
    try {
      const p2 = await env.position('2', { sale: 50, purchase: 30 });
      await env.send([p2.id]);
      const p = await env.clientPos(p2.id);
      const rep = await env.as('manager', 'POST', `/api/purchase-positions/${p2.id}/variants/replace`, {
        materialId: p2.materialId, salePrice: 55, reason: 'REPRICED',
      });
      expect(rep.status, rep.text).toBe(200);
      const body = { optionId: p.openRound.options[0].optionId, expectedUnitSalePrice: 50 };
      expect(await env.err('client', 'POST', `${CL}/rounds/${p.openRound.roundId}/accept`, body, 409, 'error.positionApproval.stale')).toBeNull();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A3-04 a repeated accept is rejected', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      const p = await env.clientPos(p1.id);
      const body = { optionId: p.openRound.options[0].optionId, expectedUnitSalePrice: 100 };
      expect((await env.as('client', 'POST', `${CL}/rounds/${p.openRound.roundId}/accept`, body)).status).toBeLessThan(300);
      expect(await env.err('client', 'POST', `${CL}/rounds/${p.openRound.roundId}/accept`, body, 409, 'error.positionApproval.round.notOpen')).toBeNull();
    } finally {
      await env.cleanup();
    }
  });

  test("TC-A3-05 another project's client cannot accept", async () => {
    const env = await Env.create({ second: true });
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      const p = await env.clientPos(p1.id);
      const body = { optionId: p.openRound.options[0].optionId, expectedUnitSalePrice: 100 };
      const r = await env.as('client2', 'POST', `${CL}/rounds/${p.openRound.roundId}/accept`, body);
      expect(r.status).toBe(404);
      expect((await env.clientPos(p1.id)).openRound.status).toBe('OPEN');
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A4 client rejects', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A4-01 reject with a reason', async () => {
    const env = await Env.create();
    try {
      const p3 = await env.position('3', { sale: 200, purchase: 150, qty: 3, block: 2 });
      await env.send([p3.id], 3);
      const roundId = await env.roundId(p3.id);
      const comment = `qa-${env.run} дорого`;
      const r = await env.as('client', 'POST', `${CL}/rounds/${roundId}/reject`, { comment });
      expect(r.status, r.text).toBeLessThan(300);
      const s = await env.staffPos(p3.id);
      expect(s.statusCode).toBe('PLANNED');
      expect(s.history.find((h: any) => h.roundId === roundId).status).toBe('REJECTED');
      const c = await env.clientPos(p3.id);
      expect(c.clientStatus).toBe('NOT_STARTED');
      expect(c.history[0].decisionComment).toBe(comment);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A4-02 reject without a reason', async () => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, block: 3 });
      await env.send([p6.id]);
      const roundId = await env.roundId(p6.id);
      for (const body of [{ comment: '' }, { comment: '   ' }, {}]) {
        const r = await env.as('client', 'POST', `${CL}/rounds/${roundId}/reject`, body);
        expect(r.status, JSON.stringify(body)).toBe(400);
      }
      expect((await env.clientPos(p6.id)).openRound.status).toBe('OPEN');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A4-03 re-sending after a reject opens round 2', async () => {
    const env = await Env.create();
    try {
      const p3 = await env.position('3', { sale: 200, purchase: 150, qty: 3, block: 2 });
      await env.send([p3.id], 3);
      const r1 = await env.roundId(p3.id);
      await env.as('client', 'POST', `${CL}/rounds/${r1}/reject`, { comment: `qa-${env.run} too expensive` });
      const rep = await env.as('manager', 'POST', `/api/purchase-positions/${p3.id}/variants/replace`, {
        materialId: p3.materialId, salePrice: 180, reason: 'REPRICED',
      });
      expect(rep.status, rep.text).toBe(200);
      const again = await env.send([p3.id], 5);
      expect(again.status, again.text).toBe(200);
      const c = await env.clientPos(p3.id);
      expect(c.openRound.roundNo).toBe(2);
      expect(c.openRound.status).toBe('OPEN');
      expect(c.money.unitNet).toBe(180);
      expect(c.history.map((h: any) => `${h.roundNo}:${h.status}`)).toContain('1:REJECTED');
    } finally {
      await env.cleanup();
    }
  });
});
