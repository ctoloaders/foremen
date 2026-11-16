import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { Env, INTERNAL_OPTION, dayOffset, leaks, waitFor } from './support.js';

// FOR-11-02 Part A5 (analog), A6 (package choice), A7 (client suggestion), A8 (client buys himself).
// Repeatability: generator + teardown (Env, removed in `finally`).
const CL = '/api/position-approvals/client';
const ST = '/api/position-approvals/staff';

const analog = (materialId: number, price = 90, days = 5) => ({
  materialId,
  unitSalePrice: price,
  internalUnitPurchasePrice: Number(INTERNAL_OPTION),
  decisionDeadline: dayOffset(days),
});

describe('FOR-11-02 A5 analog', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A5-01 analog from PROPOSED supersedes round 1', async () => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, purchase: 50, block: 3 });
      const m2 = await env.material();
      await env.send([p6.id]);
      const r1 = await env.roundId(p6.id);
      const a = await env.as('manager', 'POST', `${ST}/positions/${p6.id}/propose-analog`, analog(m2));
      expect(a.status, a.text).toBe(200);
      const s = await env.staffPos(p6.id);
      expect(s.statusCode).toBe('PROPOSED');
      expect(s.materialId).toBe(p6.materialId);
      expect(s.openRound.kind).toBe('ANALOG');
      expect(s.openRound.status).toBe('OPEN');
      expect(s.history.find((h: any) => h.roundId === r1).status).toBe('SUPERSEDED');
      const list = await env.as('client', 'GET', `${CL}/projects/${env.projectA}`);
      const c = list.body.find((p: any) => p.positionId === p6.id);
      expect(c.openRound.kind).toBe('ANALOG');
      expect(c.openRound.options[0].money.unitNet).toBe(90);
      expect(leaks(list.text)).toEqual([]);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A5-02 accepting the analog swaps the material', async () => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, purchase: 50, block: 3 });
      const m2 = await env.material();
      await env.send([p6.id]);
      await env.as('manager', 'POST', `${ST}/positions/${p6.id}/propose-analog`, analog(m2));
      const r = await env.accept(p6.id, 90);
      expect(r.status, r.text).toBeLessThan(300);
      const s = await env.staffPos(p6.id);
      expect(s.statusCode).toBe('ACCEPTED');
      expect(s.materialId).toBe(m2);
      expect(s.salePrice).toBe(90);
      const v = await env.as('manager', 'GET', `/api/purchase-positions/${p6.id}/variants`);
      expect(v.status).toBe(200);
      expect(v.body.some((x: any) => x.materialId === p6.materialId && x.reason === 'ANALOG')).toBe(true);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A5-03 analog after accept, then withdraw returns to ACCEPTED', async () => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, purchase: 50, block: 3 });
      const m2 = await env.material();
      await env.send([p6.id]);
      expect((await env.accept(p6.id, 80)).status).toBeLessThan(300);
      const a = await env.as('manager', 'POST', `${ST}/positions/${p6.id}/propose-analog`, analog(m2));
      expect(a.status, a.text).toBe(200);
      const s = await env.staffPos(p6.id);
      expect(s.statusCode).toBe('PROPOSED');
      const w = await env.as('manager', 'POST', `${ST}/rounds/${s.openRound.roundId}/withdraw`);
      expect(w.status, w.text).toBeLessThan(300);
      expect((await env.staffPos(p6.id)).statusCode).toBe('ACCEPTED');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A5-04 stages and values that are not allowed', async () => {
    const env = await Env.create();
    try {
      const m2 = await env.material();
      const late = await env.position('6', { sale: 80, purchase: 50, block: 3 });
      env.setStatus(late.id, 'IN_PROFORMA');
      expect(await env.err('manager', 'POST', `${ST}/positions/${late.id}/propose-analog`, analog(m2), 409, 'error.positionApproval.stage.notAllowed')).toBeNull();

      const p1 = await env.position('1', { sale: 100, purchase: 50 });
      await env.send([p1.id]);
      expect(await env.err('manager', 'POST', `${ST}/positions/${p1.id}/propose-analog`, analog(p1.materialId, 100), 409, 'error.positionApproval.option.sameAsCurrent')).toBeNull();
      const zero = await env.as('manager', 'POST', `${ST}/positions/${p1.id}/propose-analog`, analog(m2, 0));
      expect(zero.status).toBe(400);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A6 package choice', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A6-01 sending a choice of materials', async () => {
    const env = await Env.create();
    try {
      const p5 = await env.position('5', { path: 'PACKAGE', sale: null, purchase: null, block: 2 });
      const [m1, m2, m3] = [await env.material(), await env.material(), await env.material()];
      const ok = await env.as('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: [m1, m2, m3], decisionDeadline: dayOffset(7) });
      expect(ok.status, ok.text).toBe(200);
      expect((await env.staffPos(p5.id)).statusCode).toBe('PLANNED');

      const list = await env.as('client', 'GET', `${CL}/projects/${env.projectA}`);
      const c = list.body.find((p: any) => p.positionId === p5.id);
      expect(c.openRound.kind).toBe('PACKAGE_CHOICE');
      expect(c.openRound.options).toHaveLength(3);
      for (const o of c.openRound.options) expect(o.money).toBeNull();
      expect(c.money).toBeNull();
      expect(leaks(list.text)).toEqual([]);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A6-01 step 2 one or six materials are invalid', async () => {
    const env = await Env.create();
    try {
      const p5 = await env.position('5', { path: 'PACKAGE', sale: null, purchase: null, block: 2 });
      const mats: number[] = [];
      for (let i = 0; i < 6; i++) mats.push(await env.material());
      expect(await env.err('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: [mats[0]], decisionDeadline: dayOffset(7) }, 400, 'error.positionApproval.choice.optionsInvalid')).toBeNull();
      expect(await env.err('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: mats, decisionDeadline: dayOffset(7) }, 400, 'error.positionApproval.choice.optionsInvalid')).toBeNull();
    } finally {
      await env.cleanup();
    }
  });

  // BUG-2 (FINDINGS.md): PositionApprovalClientController.AcceptRequest.expectedUnitSalePrice is @NotNull, so a package choice (no price) is a 400.
  test('TC-A6-02 the client picks the second material (no price needed)', async () => {
    const env = await Env.create();
    try {
      const p5 = await env.position('5', { path: 'PACKAGE', sale: null, purchase: null, block: 2 });
      const [m1, m2, m3] = [await env.material(), await env.material(), await env.material()];
      await env.as('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: [m1, m2, m3], decisionDeadline: dayOffset(7) });
      const c = await env.clientPos(p5.id);
      const chosen = c.openRound.options[1];
      const r = await env.as('client', 'POST', `${CL}/rounds/${c.openRound.roundId}/accept`, { optionId: chosen.optionId });
      expect(r.status, r.text).toBeLessThan(300);
      const s = await env.staffPos(p5.id);
      expect(s.statusCode).toBe('PLANNED');
      expect(s.materialId).toBe(m2);
      expect(s.salePrice).toBeNull();
      const after = await env.clientPos(p5.id);
      expect(after.history[0].status).toBe('ACCEPTED');
      expect(after.history[0].chosenOptionId).toBe(chosen.optionId);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A7 client suggestion', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A7-01 link validation', async () => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      expect((await env.accept(p1.id, 100)).status).toBeLessThan(300);
      for (const link of ['not a url', 'ftp://x']) {
        const key = 'error.positionApproval.suggestion.linkInvalid';
        expect(await env.err('client', 'POST', `${CL}/positions/${p1.id}/suggest`, { link }, 400, key), link).toBeNull();
      }
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A7-02 suggestion is created, resolved by an analog, or declined', async () => {
    const env = await Env.create();
    try {
      const m2 = await env.material();
      const pa = await env.position('1', { sale: 100, purchase: 50 });
      const pb = await env.position('2', { sale: 50, purchase: 30 });
      await env.send([pa.id, pb.id]);
      expect((await env.accept(pa.id, 100)).status).toBeLessThan(300);
      expect((await env.accept(pb.id, 50)).status).toBeLessThan(300);

      const link = `https://shop.example/qa-${env.run}`;
      const sg = await env.as('client', 'POST', `${CL}/positions/${pa.id}/suggest`, { link, comment: 'хочу вот этот' });
      expect(sg.status, sg.text).toBeLessThan(300);
      let s = await env.staffPos(pa.id);
      expect(s.statusCode).toBe('ACCEPTED');
      expect(s.openSuggestions).toHaveLength(1);
      expect(s.openSuggestions[0].suggestionLink).toBe(link);
      const sugId = s.openSuggestions[0].roundId;

      // manager is notified (TC-A12-04)
      const got = await waitFor(async () => env.notifs('manager').find((n) => n.type === 'notification.position.suggestionReceived'));
      expect(got, 'suggestionReceived notification').toBeTruthy();

      const an = await env.as('manager', 'POST', `${ST}/positions/${pa.id}/propose-analog`, { ...analog(m2), sourceSuggestionId: sugId });
      expect(an.status, an.text).toBe(200);
      const rounds = await env.as('manager', 'GET', `${ST}/positions/${pa.id}/rounds`);
      expect(rounds.body.find((r: any) => r.roundId === sugId).status).toBe('RESOLVED');

      // second suggestion on another position is declined with a reason
      const sg2 = await env.as('client', 'POST', `${CL}/positions/${pb.id}/suggest`, { link: `${link}-2` });
      expect(sg2.status, sg2.text).toBeLessThan(300);
      s = await env.staffPos(pb.id);
      const id2 = s.openSuggestions[0].roundId;
      const note = 'нет в наличии';
      const d = await env.as('manager', 'POST', `${ST}/suggestions/${id2}/decline`, { note });
      expect(d.status, d.text).toBeLessThan(300);
      const rr = await env.as('manager', 'GET', `${ST}/positions/${pb.id}/rounds`);
      expect(rr.body.find((r: any) => r.roundId === id2).status).toBe('DECLINED');
      const c = await env.clientPos(pb.id);
      const seen = JSON.stringify(c.history);
      expect(seen).toContain(note);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 A8 client buys it himself', { tags: ['for-11-02', 'api'] }, () => {
  test('TC-A8-01 from PROPOSED', async () => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, purchase: 50, block: 3 });
      await env.send([p6.id]);
      const r1 = await env.roundId(p6.id);
      const r = await env.as('client', 'POST', `${CL}/positions/${p6.id}/client-own`, { comment: 'qa' });
      expect(r.status, r.text).toBeLessThan(300);
      const s = await env.staffPos(p6.id);
      expect(s.sourcingPath).toBe('CLIENT_OWN');
      expect(s.statusCode).toBe('PLANNED');
      expect(s.history.find((h: any) => h.roundId === r1).status).toBe('SUPERSEDED');
      const own = [s.openRound, ...s.history].find((h: any) => h && h.kind === 'CLIENT_OWN');
      expect(own.status).toBe('DECLARED');
      const c = await env.clientPos(p6.id);
      expect(c.clientStatus).toBe('CLIENT_OWN');
      expect(c.money).toBeNull();
      const audit = await env.as('manager', 'GET', `/api/purchase-positions/audit/${p6.id}`);
      expect(JSON.stringify(audit.body)).toContain('CLIENT_OWN');
      const tr = await env.as('manager', 'POST', `/api/purchase-positions/${p6.id}/transition`, { target: 'IN_DELIVERY' });
      expect(tr.status, tr.text).toBe(200);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A8-02 from ACCEPTED', async () => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, purchase: 50, block: 3 });
      await env.send([p6.id]);
      expect((await env.accept(p6.id, 80)).status).toBeLessThan(300);
      const r = await env.as('client', 'POST', `${CL}/positions/${p6.id}/client-own`, { comment: 'qa' });
      expect(r.status, r.text).toBeLessThan(300);
      const s = await env.staffPos(p6.id);
      expect(s.sourcingPath).toBe('CLIENT_OWN');
      expect(s.statusCode).toBe('PLANNED');
      expect(s.decisionDeadline).toBeNull();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-A8-03 not allowed for PACKAGE and late stages', async () => {
    const env = await Env.create();
    try {
      const p5 = await env.position('5', { path: 'PACKAGE', sale: null, purchase: null, block: 2 });
      const [m1, m2] = [await env.material(), await env.material()];
      await env.as('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: [m1, m2], decisionDeadline: dayOffset(7) });
      expect(await env.err('client', 'POST', `${CL}/positions/${p5.id}/client-own`, { comment: 'x' }, 409, 'error.positionApproval.clientOwn.notAllowed')).toBeNull();

      const p6 = await env.position('6', { sale: 80, purchase: 50, block: 3 });
      await env.send([p6.id]);
      await env.accept(p6.id, 80);
      env.setStatus(p6.id, 'IN_PROFORMA');
      const late = await env.as('client', 'POST', `${CL}/positions/${p6.id}/client-own`, { comment: 'x' });
      expect(late.status).toBe(409);
    } finally {
      await env.cleanup();
    }
  });
});
