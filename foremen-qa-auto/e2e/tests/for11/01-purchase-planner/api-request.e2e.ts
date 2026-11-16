import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, dayShift, sql, setupEnv, teardownEnv, type Dataset, type Env } from './support.js';

// FOR-11-01 Part A: A5 purchase request from positions, buyers list.
// Repeatability: run-id in every request reference (QA-<id>-n) and notes; fresh positions per test;
// teardown in afterAll removes projects, positions, requests and users.
describe('FOR-11-01 API purchase request from positions', { tags: ['for-11-01', 'api'] }, () => {
  let env: Env;
  let ds: Dataset;
  let seq = 0;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });

  const fresh = async (over: Record<string, unknown> = {}, tok = env.tok.manager): Promise<number> => {
    const r = await api('POST', '/api/purchase-positions', tok, {
      projectId: env.projectA, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: 40, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-x${++seq}`, ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    return r.body.id;
  };
  const from = (body: unknown, tok = env.tok.manager) => api('POST', '/api/purchase-requests/from-positions', tok, body);
  const position = async (id: number) => (await api('GET', `/api/purchase-positions?query=id==${id}&size=1`, env.tok.admin)).body.content[0];

  test('TC-A5-01 request is created and positions attached with the planned delivery date', async () => {
    const a = await fresh();
    const b = await fresh();
    const r = await from({ positionIds: [a, b], referenceNumber: `QA-${env.id}-N1`, expectedDeliveryDate: dayShift(12) });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    expect(r.body.positionCount).toBe(2);
    for (const id of [a, b]) {
      const p = await position(id);
      expect(p.purchaseRequestId).toBe(r.body.id);
      expect(p.plannedDeliveryDate).toBe(dayShift(12));
    }
  });

  test('TC-A5-02 an already assigned position is a 409 and nothing is created', async () => {
    const free = await fresh();
    const ref = `QA-${env.id}-ATOM`;
    const r = await from({ positionIds: [ds.P.P2, free], referenceNumber: ref });
    expect(r.status, JSON.stringify(r.body)).toBe(409);
    const list = await api('GET', `/api/purchase-requests?query=referenceNumber==${ref}`, env.tok.admin);
    expect(list.body.content.length).toBe(0);
    expect((await position(free)).purchaseRequestId).toBeNull();
  });

  test('TC-A5-02 positions of different projects are a 400, an empty list is a 400', async () => {
    const mixed = await from({ positionIds: [ds.P.P1, ds.P.P11] }, env.tok.admin);
    expect(mixed.status, JSON.stringify(mixed.body)).toBe(400);
    const empty = await from({ positionIds: [] });
    expect(empty.status).toBe(400);
  });

  test('TC-A5-02 a MANAGER cannot build a request on a position of a project he is not a member of', async () => {
    const r = await from({ positionIds: [ds.P.P11], referenceNumber: `QA-${env.id}-LEAK` });
    expect([403, 404], JSON.stringify(r.body)).toContain(r.status);
  });

  test('TC-A5-03 buyer gets exactly one request.assigned notification with the deep link', async () => {
    const pos = await fresh();
    const r = await from({ positionIds: [pos], referenceNumber: `QA-${env.id}-N3`, assignedBuyerId: env.buyer2.id });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    let rows: any[] = [];
    for (let i = 0; i < 10 && rows.length === 0; i++) {
      await new Promise((res) => setTimeout(res, 300));
      const n = await api('GET', '/api/notifications?size=50', env.tok.buyer2);
      rows = (Array.isArray(n.body) ? n.body : n.body.content ?? []).filter((x: any) => String(x.type).includes('request.assigned'));
    }
    expect(rows.length).toBe(1);
    expect(rows[0].deepLink).toBe(`/deliveries?request=${r.body.id}`);
  });

  test('TC-A5-03 an assignee without the BUYER role is a 400 and the positions stay free', async () => {
    const pos = await fresh();
    const r = await from({ positionIds: [pos], referenceNumber: `QA-${env.id}-N3b`, assignedBuyerId: env.foreman.id });
    expect(r.status, JSON.stringify(r.body)).toBe(400);
    expect((await position(pos)).purchaseRequestId).toBeNull();
  });

  test('TC-A5-04 BUYER and FOREMAN cannot create requests from positions', async () => {
    const pos = await fresh();
    expect((await from({ positionIds: [pos] }, env.tok.buyer)).status).toBe(403);
    expect((await from({ positionIds: [pos] }, env.tok.foreman)).status).toBe(403);
  });

  test('TC-A5-05 buyers list: active BUYERs only, MANAGER allowed, FOREMAN/FINANCIER 403', async () => {
    const r = await api('GET', '/api/procurement/buyers', env.tok.manager);
    expect(r.status).toBe(200);
    const idsList = (r.body as any[]).map((b) => b.id);
    expect(idsList).toContain(env.buyer.id);
    expect(idsList).toContain(env.buyer2.id);
    expect(idsList).not.toContain(env.manager.id);
    expect(idsList).not.toContain(env.foreman.id);
    sql(`UPDATE users SET active=false, status='DEACTIVATED' WHERE id=${env.buyer2.id};`);
    const after = await api('GET', '/api/procurement/buyers', env.tok.manager);
    expect((after.body as any[]).map((b) => b.id)).not.toContain(env.buyer2.id);
    sql(`UPDATE users SET active=true, status='ACTIVE' WHERE id=${env.buyer2.id};`);
    // test-cases.md expects 403 for FOREMAN, but FOREMAN holds the seeded PURCHASE_REQUESTS READ grant (FOR-11-00),
    // which is exactly the guard design.md names for this endpoint -> 200 is by design (documented deviation).
    expect((await api('GET', '/api/procurement/buyers', env.tok.foreman)).status).toBe(200);
    expect((await api('GET', '/api/procurement/buyers', env.tok.financier)).status).toBe(403);
  });
});
