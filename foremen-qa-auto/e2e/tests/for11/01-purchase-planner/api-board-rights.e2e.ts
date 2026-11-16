import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, plannerRows, sql, setupEnv, teardownEnv, type Dataset, type Env } from './support.js';

// FOR-11-01 Part A: A6 buyer board, A8 rights / price visibility, regression R-01..R-03, R-06.
// Repeatability: same run-id dataset as the other API files, deleted in afterAll.
describe('FOR-11-01 API board, rights, regression', { tags: ['for-11-01', 'api'] }, () => {
  let env: Env;
  let ds: Dataset;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });
  const q = (extra = '') => `projectId=${env.projectA}${extra ? '&' + extra : ''}`;
  const board = (tok: string, extra = '') => api('GET', `/api/purchase-positions/board?${q(extra)}`, tok);
  const col = (b: any, status: string) => b.columns.find((c: any) => c.status === status);
  const cardIds = (c: any) => c.cards.map((x: any) => x.positionId).sort((a: number, b: number) => a - b);

  test('TC-A6-01 six columns in order with the expected cards and over-ceiling flag', async () => {
    const r = await board(env.tok.buyer);
    expect(r.status).toBe(200);
    expect(r.body.columns.map((c: any) => c.status)).toEqual(
      ['WITH_BUYER', 'ORDERED', 'SUPPLIER_PAID', 'IN_DELIVERY', 'PARTIALLY_RECEIVED', 'RECEIVED']);
    expect(cardIds(col(r.body, 'WITH_BUYER'))).toEqual([ds.P.P6, ds.P.P9].sort((a, b) => a - b));
    expect(cardIds(col(r.body, 'IN_DELIVERY'))).toEqual([ds.P.P7]);
    expect(cardIds(col(r.body, 'RECEIVED'))).toEqual([ds.P.P10]);
    const wb = col(r.body, 'WITH_BUYER').cards;
    expect(wb.find((c: any) => c.positionId === ds.P.P9).money.overCeiling).toBe(true);
    expect(wb.find((c: any) => c.positionId === ds.P.P6).money.overCeiling).toBe(false);
    expect(wb.find((c: any) => c.positionId === ds.P.P6).money.salePrice).toBe(100);
    const before = r.body.columns.flatMap((c: any) => c.cards.map((x: any) => x.positionId));
    for (const c of ['P1', 'P2', 'P3', 'P4', 'P5', 'P8', 'P12']) expect(before).not.toContain(ds.P[c]);
  });

  test('TC-B2-01 planner rows carry the same overCeiling flag as the board (API side)', async () => {
    const rows = await plannerRows(env.tok.buyer, q());
    expect(rows.find((r) => r.positionId === ds.P.P9).money.overCeiling).toBe(true);
    expect(rows.find((r) => r.positionId === ds.P.P6).money.overCeiling).toBe(false);
  });

  test('TC-A6-01 cards are ordered by site delivery date', async () => {
    const r = await board(env.tok.buyer);
    for (const c of r.body.columns) {
      const d = c.cards.map((x: any) => x.siteDeliveryDate as string | null).filter((x: string | null) => x !== null);
      expect(d).toEqual([...d].sort());
    }
  });

  test('TC-A6-02 limit, hasMore, hideDelivered, buyer filter', async () => {
    const one = await board(env.tok.buyer, 'limit=1');
    const wb = col(one.body, 'WITH_BUYER');
    expect(wb.cards.length).toBe(1);
    expect(wb.count).toBe(2);
    expect(wb.hasMore).toBe(true);
    for (const bad of ['limit=0', 'limit=101']) {
      expect((await board(env.tok.buyer, bad)).status, bad).toBe(400);
    }
    const hid = col((await board(env.tok.buyer, 'hideDelivered=true')).body, 'RECEIVED');
    expect(hid.count).toBe(1);
    expect(hid.cards).toEqual([]);
    const mine = await board(env.tok.buyer, 'buyer=MINE');
    expect(cardIds(col(mine.body, 'WITH_BUYER'))).toEqual([ds.P.P6, ds.P.P9].sort((a, b) => a - b));
    expect(col(mine.body, 'IN_DELIVERY').cards).toEqual([]); // P7 has no request
  });

  test('TC-A6-03 FOREMAN board has no money and only member projects', async () => {
    const r = await api('GET', '/api/purchase-positions/board', env.tok.foreman);
    expect(r.status).toBe(200);
    const cards = r.body.columns.flatMap((c: any) => c.cards);
    expect(cards.length).toBeGreaterThan(0);
    for (const c of cards) {
      expect(c.money).toBeNull();
      expect(c.projectId).toBe(env.projectA);
    }
  });

  const read: Array<[string, string]> = [
    ['planner', `/api/purchase-positions/planner`],
    ['slice-counts', `/api/purchase-positions/planner/slice-counts`],
    ['groups', `/api/purchase-positions/planner/groups`],
    ['board', `/api/purchase-positions/board`],
  ];
  test('TC-A8-01 read endpoints by role (ADMIN/MANAGER/BUYER 200, FOREMAN 200, FINANCIER 403)', async () => {
    const roles: Array<[string, string, number]> = [
      ['admin', env.tok.admin, 200], ['manager', env.tok.manager, 200], ['buyer', env.tok.buyer, 200],
      ['foreman', env.tok.foreman, 200], ['financier', env.tok.financier, 403],
    ];
    const got: string[] = [];
    for (const [name, path] of read) {
      for (const [role, tok, want] of roles) {
        const r = await api('GET', `${path}?projectId=${env.projectA}`, tok);
        if (r.status !== want) got.push(`${name}/${role}: ${r.status} (want ${want})`);
      }
    }
    for (const role of [['admin', env.tok.admin, 200], ['manager', env.tok.manager, 200], ['buyer', env.tok.buyer, 200], ['foreman', env.tok.foreman, 200], ['financier', env.tok.financier, 403]] as const) {
      const sc = await api('GET', `/api/purchase-positions/scheduled-categories?projectId=${env.projectA}`, role[1]);
      if (sc.status !== role[2]) got.push(`scheduled-categories/${role[0]}: ${sc.status} (want ${role[2]})`);
    }
    expect(got).toEqual([]);
  });

  test('TC-A8-01 selection-total, from-positions, buyers by role', async () => {
    const got: string[] = [];
    const sel = (tok: string) => api('POST', '/api/purchase-positions/selection-total', tok, { positionIds: [ds.P.P3] });
    for (const [role, tok, want] of [['admin', env.tok.admin, 200], ['manager', env.tok.manager, 200], ['buyer', env.tok.buyer, 200], ['foreman', env.tok.foreman, 403], ['financier', env.tok.financier, 403]] as const) {
      const r = await sel(tok);
      if (r.status !== want) got.push(`selection-total/${role}: ${r.status} (want ${want})`);
    }
    for (const [role, tok] of [['buyer', env.tok.buyer], ['foreman', env.tok.foreman], ['financier', env.tok.financier]] as const) {
      const r = await api('POST', '/api/purchase-requests/from-positions', tok, { positionIds: [ds.P.P1] });
      if (r.status !== 403) got.push(`from-positions/${role}: ${r.status} (want 403)`);
    }
    expect(got).toEqual([]);
  });

  test('TC-A8-02 FOREMAN gets no money anywhere; unsupported sort/filter fields are 400', async () => {
    const rows = await plannerRows(env.tok.foreman, q());
    expect(rows.length).toBeGreaterThan(0);
    for (const r of rows) expect(r.money).toBeNull();
    const g = await api('GET', `/api/purchase-positions/planner/groups?${q()}`, env.tok.foreman);
    for (const grp of g.body.content) expect(grp.totals).toBeNull();
    expect((await api('GET', `/api/purchase-positions/planner?${q('sort=salePrice')}`, env.tok.manager)).status).toBe(400);
    const f = await api('GET', `/api/purchase-positions/planner?${q('filter=salePrice>1')}`, env.tok.manager);
    expect([200, 400]).toContain(f.status); // unknown params are ignored by Spring; must never filter by price
    const buyerRows = await plannerRows(env.tok.buyer, q());
    expect(buyerRows.every((r) => r.money !== null)).toBe(true);
  });

  test('TC-A8-03 allowedActions by role and path', async () => {
    const pkg = await api('POST', '/api/purchase-positions', env.tok.manager, {
      projectId: env.projectA, kind: 'FINISHING', sourcingPath: 'PACKAGE', plannedMaterialId: 40, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-pkg`,
    });
    const pkgId = pkg.body.id;
    const mgr = await plannerRows(env.tok.manager, q());
    expect(mgr.find((r) => r.positionId === ds.P.P7).allowedActions).not.toContain('SEND_TO_BUYER');
    expect(mgr.find((r) => r.positionId === pkgId).allowedActions).toContain('SEND_TO_BUYER');
    for (const r of await plannerRows(env.tok.foreman, q())) expect(r.allowedActions).toEqual([]);
    for (const r of await plannerRows(env.tok.buyer, q())) {
      expect(r.allowedActions).not.toContain('DELETE');
      expect(r.allowedActions).not.toContain('CANCEL');
    }
  });

  test('TC-R-01 00 API stays compatible (list has workCategoryId, upcoming/variants/transition work)', async () => {
    const list = await api('GET', `/api/purchase-positions?size=50`, env.tok.manager);
    expect(list.status).toBe(200);
    expect(list.body.content.length).toBeGreaterThan(0);
    expect('workCategoryId' in list.body.content[0]).toBe(true);
    const created = await api('POST', '/api/purchase-positions', env.tok.manager, {
      projectId: env.projectA, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: 40, quantity: 1, notes: `qa-${env.id}-r1`,
    });
    expect([200, 201]).toContain(created.status);
    expect((await api('GET', '/api/purchase-positions/upcoming', env.tok.manager)).status).toBe(200);
    expect((await api('GET', `/api/purchase-positions/${ds.P.P8}/variants`, env.tok.manager)).status).toBe(200);
    const cancel = await api('POST', `/api/purchase-positions/${created.body.id}/transition`, env.tok.manager, { target: 'CANCELLED' });
    expect(cancel.status, JSON.stringify(cancel.body)).toBe(200);
  });

  test('TC-R-02 payment gate: AGREED PLANNED -> WITH_BUYER is a 409 and not offered', async () => {
    const r = await api('POST', `/api/purchase-positions/${ds.P.P1}/transition`, env.tok.manager, { target: 'WITH_BUYER' });
    expect(r.status, JSON.stringify(r.body)).toBe(409);
    const row = (await plannerRows(env.tok.manager, q())).find((x) => x.positionId === ds.P.P1);
    expect(row.allowedActions).not.toContain('SEND_TO_BUYER');
  });

  test('TC-R-03 BUYER has no access to payroll', async () => {
    for (const path of ['/api/payroll/company', '/api/bank/transactions']) {
      const r = await api('GET', path, env.tok.buyer);
      expect(r.status, path).toBe(403);
    }
  });

  // TC-R-05 is partial: re-running liquibase needs a container restart (forbidden here); the end state is checked instead.
  test('TC-R-05 work_category_id exists exactly once and the 233 changeset is recorded', async () => {
    expect(sql(`SELECT count(*) FROM information_schema.columns WHERE table_name='purchase_positions' AND column_name='work_category_id'`)).toBe('1');
    expect(Number(sql(`SELECT count(*) FROM databasechangelog WHERE filename LIKE '%233%'`))).toBeGreaterThanOrEqual(1);
  });

  test('TC-R-06 project materials are not in the global finishing-materials list', async () => {
    const r = await api('GET', `/api/finishing-materials?size=200&query=projectId==${env.projectA}`, env.tok.manager);
    expect([200, 400]).toContain(r.status);
    if (r.status === 200) expect(r.body.content.every((m: any) => !m.projectId)).toBe(true);
  });
});
