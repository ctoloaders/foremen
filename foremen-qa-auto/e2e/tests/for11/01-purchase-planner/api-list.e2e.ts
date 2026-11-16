import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, plannerRows, setupEnv, teardownEnv, type Dataset, type Env } from './support.js';

// FOR-11-01 Part A: A1 list, A2 slices/urgency, A3 groups, A4 selection total.
// Repeatability: one run-id per file (users F11 run-id, projects F11-<id>-A/B, notes qa-<id>-n); everything is
// deleted in afterAll (support.teardownEnv).
describe('FOR-11-01 API list, slices, groups, selection', { tags: ['for-11-01', 'api'] }, () => {
  let env: Env;
  let ds: Dataset;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });

  const ids = (rows: any[]) => rows.map((r) => r.positionId as number);
  const set = (...codes: string[]) => codes.map((c) => ds.P[c]).sort((a, b) => a - b);
  const sorted = (a: number[]) => [...a].sort((x, y) => x - y);
  const q = (extra = '') => `projectId=${env.projectA}${extra ? '&' + extra : ''}`;

  test('TC-A1-01 planner is sorted by siteDeliveryDate asc, nulls last, ties by id', async () => {
    const rows = await plannerRows(env.tok.manager, q());
    const dates = rows.map((r) => r.siteDeliveryDate as string | null);
    const firstNull = dates.findIndex((d) => d === null);
    const dated = firstNull === -1 ? dates : dates.slice(0, firstNull);
    expect(dated).toEqual([...dated].sort());
    if (firstNull !== -1) expect(dates.slice(firstNull).every((d) => d === null)).toBe(true);
    for (let i = 1; i < rows.length; i++) {
      if (rows[i].siteDeliveryDate && rows[i].siteDeliveryDate === rows[i - 1].siteDeliveryDate) {
        expect(rows[i].positionId).toBeGreaterThan(rows[i - 1].positionId);
      }
    }
    const desc = await plannerRows(env.tok.manager, q('sort=siteDeliveryDate,desc'));
    const dd = desc.map((r) => r.siteDeliveryDate as string | null);
    const lastDated = dd.map((d) => d !== null).lastIndexOf(true);
    expect(dd.slice(0, lastDated + 1).every((d) => d !== null)).toBe(true); // nulls stay last
    const datedDesc = dd.slice(0, lastDated + 1) as string[];
    expect(datedDesc).toEqual([...datedDesc].sort().reverse());
  });

  test('TC-A1-01 unknown sort field is a 400 with a localized message', async () => {
    const r = await api('GET', `/api/purchase-positions/planner?${q('sort=unknownField')}`, env.tok.manager);
    expect(r.status, JSON.stringify(r.body)).toBe(400);
    expect(r.body.message).toBe('Nieprawidłowe pole sortowania.');
    const ru = await (await fetch(`http://localhost:8080/api/purchase-positions/planner?${q('sort=unknownField')}`, {
      headers: { Authorization: `Bearer ${env.tok.manager}`, 'Accept-Language': 'ru' },
    })).json();
    expect(ru.message).not.toBe(r.body.message);
  });

  test('TC-A1-02 row composition and money of P3', async () => {
    for (const lang of ['pl', 'ru']) {
      const r = await (await fetch(`http://localhost:8080/api/purchase-positions/planner?${q()}&size=100`, {
        headers: { Authorization: `Bearer ${env.tok.manager}`, 'Accept-Language': lang },
      })).json();
      const p3 = r.content.find((x: any) => x.positionId === ds.P.P3);
      expect(p3.quantity).toBe(12.5);
      expect(p3.unitName).toBe(lang === 'pl' ? 'm²' : 'м²');
      expect(p3.requestReferenceNumber).toBe(ds.R1ref);
      expect(p3.siteDeliveryDate).toBeTruthy();
      expect(p3.allowedActions).toContain('EDIT');
      expect(p3.allowedActions).toContain('CANCEL');
      expect(p3.material.model).toBeTruthy();
      expect(p3.money.saleNetTotal).toBe(1150);
      expect(p3.money.saleGrossTotal).toBe(1242);
      expect(p3.money.costNetTotal).toBe(768.75);
      expect(p3.money.marginAmount).toBe(381.25);
      expect(p3.money.marginPct).toBeCloseTo(0.3315, 3);
    }
  });

  test('TC-A1-03 status / sourcingPath / requestId filters', async () => {
    expect(sorted(ids(await plannerRows(env.tok.manager, q('status=ACCEPTED'))))).toEqual(set('P3', 'P4'));
    expect(sorted(ids(await plannerRows(env.tok.manager, q('status=ACCEPTED&sourcingPath=AGREED'))))).toEqual(set('P3', 'P4'));
    expect(sorted(ids(await plannerRows(env.tok.manager, q(`requestId=${ds.R1}`))))).toEqual(set('P2', 'P3'));
  });

  test('TC-A1-03 noRequest filter', async () => {
    const got = sorted(ids(await plannerRows(env.tok.manager, q('noRequest=true'))));
    expect(got).toEqual(set('P1', 'P4', 'P5', 'P7', 'P8', 'P10', 'P12'));
  });

  test('TC-A1-03 q searches text and treats % and _ literally', async () => {
    const byNote = await plannerRows(env.tok.manager, q(`q=qa-${env.id}-12`));
    expect(ids(byNote)).toEqual(set('P12'));
    for (const wild of ['%25', '_', '%25%25']) {
      const rows = await plannerRows(env.tok.manager, q(`q=${wild}`));
      expect(rows.length, `q=${wild}`).toBe(0);
    }
  });

  test('TC-A1-03 buyer=MINE / UNASSIGNED', async () => {
    const mine = await plannerRows(env.tok.buyer, q('buyer=MINE'));
    expect(sorted(ids(mine))).toEqual(set('P6', 'P9'));
    const un = await plannerRows(env.tok.buyer, q('buyer=UNASSIGNED'));
    expect(sorted(ids(un))).toEqual(set('P1', 'P2', 'P3', 'P4', 'P5', 'P7', 'P8', 'P10', 'P12'));
  });

  test('TC-A1-03 pagination and totalElements', async () => {
    const r = await api('GET', `/api/purchase-positions/planner?${q('size=4&page=1')}`, env.tok.manager);
    expect(r.status).toBe(200);
    expect(r.body.totalElements).toBe(11);
    expect(r.body.content.length).toBe(4);
    expect(r.body.totalPages).toBe(3);
  });

  test('TC-A1-04 hideDelivered removes the RECEIVED position', async () => {
    const all = await plannerRows(env.tok.manager, q());
    const hid = await plannerRows(env.tok.manager, q('hideDelivered=true'));
    expect(ids(all)).toContain(ds.P.P10);
    expect(ids(hid)).not.toContain(ds.P.P10);
    expect(hid.length).toBe(all.length - 1);
  });

  test('TC-A1-05 BUYER sees all projects without projectId, MANAGER only own', async () => {
    const buyerRows = await plannerRows(env.tok.buyer, `q=qa-${env.id}`);
    expect(ids(buyerRows)).toContain(ds.P.P11);
    expect(ids(buyerRows)).toContain(ds.P.P1);
    const mgrRows = await plannerRows(env.tok.manager, `q=qa-${env.id}`);
    expect(ids(mgrRows)).not.toContain(ds.P.P11);
    expect(ids(mgrRows)).toContain(ds.P.P1);
  });

  test('TC-A2-01 slice definitions', async () => {
    const slice = async (s: string) => sorted(ids(await plannerRows(env.tok.manager, q(`slice=${s}`))));
    expect(await slice('LATE')).toEqual(set('P2'));
    const onSchedule = await slice('ON_SCHEDULE');
    expect(onSchedule).not.toContain(ds.P.P2);
    expect(onSchedule).not.toContain(ds.P.P10);
    expect(onSchedule).toContain(ds.P.P1);
    expect(await slice('CHANGED')).toEqual(set('P8'));
    // PAID = AGREED at CLIENT_PAID or later (requirements R2.2): P5 and the RECEIVED P10, never PACKAGE/CLIENT_OWN
    const paid = await slice('PAID');
    expect(paid).toContain(ds.P.P5);
    for (const c of ['P6', 'P7', 'P9']) expect(paid).not.toContain(ds.P[c]);
    expect(await slice('NOT_PAID')).toEqual(set('P1', 'P2', 'P3', 'P4', 'P8', 'P12'));
  });

  test('TC-A2-01 an invalid slice is a 400', async () => {
    const r = await api('GET', `/api/purchase-positions/planner?${q('slice=garbage')}`, env.tok.manager);
    expect(r.status, JSON.stringify(r.body)).toBe(400);
  });

  test('TC-A2-02 slice-counts equal the slice selections', async () => {
    const c = await api('GET', `/api/purchase-positions/planner/slice-counts?${q()}`, env.tok.manager);
    expect(c.status).toBe(200);
    for (const s of ['ALL', 'ON_SCHEDULE', 'LATE', 'CHANGED', 'PAID', 'NOT_PAID']) {
      const r = await api('GET', `/api/purchase-positions/planner?${q(`slice=${s}&size=1`)}`, env.tok.manager);
      expect(c.body[s], s).toBe(r.body.totalElements);
    }
  });

  test('TC-A2-03 urgent filter equals the urgent flag', async () => {
    const rows = await plannerRows(env.tok.manager, q('urgent=true'));
    expect(sorted(ids(rows))).toEqual(set('P2', 'P3', 'P6', 'P9', 'P12'));
    const all = await plannerRows(env.tok.manager, q());
    expect(sorted(ids(all.filter((r) => r.urgent)))).toEqual(sorted(ids(rows)));
  });

  test('TC-A3-01 groups by request, ordered by earliest delivery date, with totals', async () => {
    const r = await api('GET', `/api/purchase-positions/planner/groups?${q('groupBy=REQUEST')}`, env.tok.manager);
    expect(r.status).toBe(200);
    const groups = r.body.content as any[];
    // earliest site date per group: R1 -3 (P2 pinned late), R2 +5, no-request +9 (P12)
    expect(groups.map((g) => g.requestId)).toEqual([ds.R1, ds.R2, null]);
    const r1 = groups.find((g) => g.requestId === ds.R1);
    expect(r1.positionCount).toBe(2);
    expect(r1.referenceNumber).toBe(ds.R1ref);
    expect(r1.assignedBuyerName).toBeNull();
    expect(r1.totals.saleNet).toBe(1200);
    expect(r1.totals.costNet).toBe(768.75);
    expect(r1.totals.marginIncomplete).toBe(true);
    const synthetic = groups.find((g) => g.requestId === null);
    expect(synthetic.positionCount).toBe(7);
    const dates = groups.map((g) => g.expectedDeliveryDate as string | null);
    const firstNull = dates.indexOf(null);
    if (firstNull !== -1) expect(dates.slice(firstNull).every((d) => d === null)).toBe(true);
  });

  test('TC-A3-02 sum of group saleNet equals the sum of the planner rows', async () => {
    const r = await api('GET', `/api/purchase-positions/planner/groups?${q('size=100')}`, env.tok.manager);
    const groupSum = (r.body.content as any[]).reduce((s, g) => s + (g.totals?.saleNet ?? 0), 0);
    const rows = await plannerRows(env.tok.manager, q());
    const rowSum = rows.reduce((s, x) => s + (x.money?.saleNetTotal ?? 0), 0);
    expect(Math.round(groupSum * 100)).toBe(Math.round(rowSum * 100));
  });

  test('TC-A3-03 groupBy NONE gives one synthetic group; unknown value is a 400', async () => {
    const none = await api('GET', `/api/purchase-positions/planner/groups?${q('groupBy=NONE')}`, env.tok.manager);
    expect(none.status).toBe(200);
    expect(none.body.content.length).toBe(1);
    const bad = await api('GET', `/api/purchase-positions/planner/groups?${q('groupBy=BOGUS')}`, env.tok.manager);
    expect(bad.status, JSON.stringify(bad.body)).toBe(400);
  });

  test('TC-A4-01 selection total sums accepted AGREED positions and classifies skips', async () => {
    const st = (b: unknown) => api('POST', '/api/purchase-positions/selection-total', env.tok.manager, b);
    const ok = await st({ positionIds: [ds.P.P3, ds.P.P4] });
    expect(ok.status).toBe(200);
    expect(ok.body.count).toBe(2);
    expect(ok.body.saleNet).toBe(1550);
    expect(ok.body.vat).toBe(124);
    expect(ok.body.saleGross).toBe(1674);
    expect(ok.body.skipped).toEqual([]);
    expect(ok.body.mixedProjects).toBe(false);
    expect(ok.body.vatBreakdown[0].ratePercent).toBe(8);
    const notAccepted = await st({ positionIds: [ds.P.P3, ds.P.P1] });
    expect(notAccepted.body.skipped).toEqual([{ positionId: ds.P.P1, reason: 'NOT_ACCEPTED' }]);
    const notAgreed = await st({ positionIds: [ds.P.P6] });
    expect(notAgreed.body.skipped[0].reason).toBe('NOT_AGREED');
    const foreign = await st({ positionIds: [ds.P.P3, ds.P.P11] });
    expect(foreign.body.skipped).toEqual([{ positionId: ds.P.P11, reason: 'NOT_FOUND' }]);
    const empty = await st({ positionIds: [] });
    expect(empty.status).toBe(400);
    const tooMany = await st({ positionIds: Array.from({ length: 501 }, (_, i) => i + 1) });
    expect(tooMany.status).toBe(400);
  });

  test('TC-A4-02 selection total: FOREMAN 403, BUYER 200', async () => {
    const body = { positionIds: [ds.P.P3] };
    expect((await api('POST', '/api/purchase-positions/selection-total', env.tok.foreman, body)).status).toBe(403);
    expect((await api('POST', '/api/purchase-positions/selection-total', env.tok.buyer, body)).status).toBe(200);
  });

  test('TC-A4-03 mixed projects flag for ADMIN', async () => {
    // an ACCEPTED position in project B is created and fixed by SQL for this case only
    const created = await api('POST', '/api/purchase-positions', env.tok.admin, {
      projectId: env.projectB, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: 40, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-B2`,
    });
    expect(created.status).toBe(200);
    const { setStatus } = await import('./support.js');
    setStatus(created.body.id, 'ACCEPTED');
    const r = await api('POST', '/api/purchase-positions/selection-total', env.tok.admin, { positionIds: [ds.P.P3, created.body.id] });
    expect(r.status).toBe(200);
    expect(r.body.mixedProjects).toBe(true);
  });
});
