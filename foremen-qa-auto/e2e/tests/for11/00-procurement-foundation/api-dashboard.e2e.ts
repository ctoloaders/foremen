import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { addItem, widgetIdByCode } from '../../support/api.js';
import { createUser, createWorld, destroyWorld, http, isoDate, newPosition, newRequest, setStatus, tokenOf, type World } from './support.js';

// FOR-11-00 Part A, A14 (dashboard widgets). Repeatability: run-id generator + teardown in `finally`.
const CODES = ['UPCOMING_DELIVERIES', 'PROCUREMENT_BY_STAGE', 'POSITIONS_WITH_BUYER'];

const layout = async (token: string) => (await http('GET', '/api/dashboard', token)).body.items as Array<{ id: number; code: string }>;
const dataOf = async (token: string, code: string) => {
  const item = (await layout(token)).find((i) => i.code === code);
  if (!item) throw new Error(`no ${code} on the dashboard`);
  return (await http('GET', `/api/dashboard/items/${item.id}/data`, token)).body;
};

/** Project A: 2 WITH_BUYER (PACKAGE), 1 PLANNED, 1 CANCELLED; the WITH_BUYER ones sit in a request dated in 3 days. */
async function seedA(w: World) {
  const req = await newRequest(w, { expectedDeliveryDate: isoDate(3) });
  const a = await newPosition(w, { sourcingPath: 'PACKAGE' });
  const b = await newPosition(w, { sourcingPath: 'PACKAGE' });
  await newPosition(w);
  const d = await newPosition(w);
  await http('POST', `/api/purchase-requests/${req.body.id}/positions`, w.tok.manager, { positionIds: [a.body.id, b.body.id] });
  for (const id of [a.body.id, b.body.id]) await http('POST', `/api/purchase-positions/${id}/transition`, w.tok.manager, { target: 'WITH_BUYER' });
  await http('POST', `/api/purchase-positions/${d.body.id}/transition`, w.tok.manager, { target: 'CANCELLED' });
  return { a: a.body.id as number, b: b.body.id as number };
}

describe('FOR-11-00 A14 dashboard widgets', { tags: ['for-11-00', 'api', 'dashboard'] }, () => {
  test('TC-A14-01 the library offers the three procurement widgets to FOREMAN/MANAGER/BUYER and not to WORKER/CLIENT', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const worker = createUser('WORKER', 'wrk');
      const client = createUser('CLIENT', 'cl');
      w.extraUsers.push(worker, client);
      const offered = async (token: string) => {
        const lib = (await http('GET', '/api/dashboard/library', token)).body as Array<{ code: string; available: boolean }>;
        return CODES.map((c) => lib.find((x) => x.code === c)).map((x) => !!x && x.available);
      };
      for (const tok of [w.tok.foreman, w.tok.manager, w.tok.buyer]) expect(await offered(tok)).toEqual([true, true, true]);
      for (const u of [worker, client]) expect(await offered(await tokenOf(u))).toEqual([false, false, false]);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A14-02 UPCOMING_DELIVERIES scope: FOREMAN own project, BUYER all projects, MANAGER only project A', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await seedA(w);
      const reqB = await newRequest(w, { projectId: w.projB, expectedDeliveryDate: isoDate(1) }, w.admin);
      const pb = await newPosition(w, { projectId: w.projB, plannedMaterialId: w.matB, sourcingPath: 'PACKAGE' }, w.admin);
      await http('POST', `/api/purchase-requests/${reqB.body.id}/positions`, w.admin, { positionIds: [pb.body.id] });
      await http('POST', `/api/purchase-positions/${pb.body.id}/transition`, w.admin, { target: 'WITH_BUYER' });
      const projectsOf = async (tok: string) => (await dataOf(tok, 'UPCOMING_DELIVERIES')).rows.map((r: any) => r['project.id']);
      const foreman = await projectsOf(w.tok.foreman);
      expect(foreman.length).toBeGreaterThanOrEqual(2);
      expect(new Set(foreman)).toEqual(new Set([w.projA]));
      const dates = (await dataOf(w.tok.foreman, 'UPCOMING_DELIVERIES')).rows.map((r: any) => r.plannedDeliveryDate);
      expect([...dates].sort()).toEqual(dates);
      const manager = await projectsOf(w.tok.manager);
      expect(new Set(manager)).toEqual(new Set([w.projA]));
      const buyer = await projectsOf(w.tok.buyer);
      expect(buyer).toContain(w.projA);
      expect(buyer).toContain(w.projB);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A14-03 new BUYER and FOREMAN users get their role-default widgets', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const buyer = (await layout(w.tok.buyer)).map((i) => i.code);
      expect(buyer).toEqual(expect.arrayContaining(CODES));
      const foreman = (await layout(w.tok.foreman)).map((i) => i.code);
      expect(foreman).toContain('UPCOMING_DELIVERIES');
      expect(foreman).not.toContain('PROCUREMENT_BY_STAGE');
      expect(foreman).not.toContain('POSITIONS_WITH_BUYER');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A14-04 PROCUREMENT_BY_STAGE excludes CANCELLED; POSITIONS_WITH_BUYER matches the positions list', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await seedA(w);
      const stage = await dataOf(w.tok.manager, 'PROCUREMENT_BY_STAGE');
      const byStatus = Object.fromEntries(stage.rows.map((r: any) => [r.status, r.cnt]));
      expect(byStatus).toEqual({ PLANNED: 1, WITH_BUYER: 2 });
      // the manager's default layout has no POSITIONS_WITH_BUYER: add it from the library
      await addItem(w.tok.manager, { widgetId: await widgetIdByCode(w.tok.manager, 'POSITIONS_WITH_BUYER') });
      const kpi = await dataOf(w.tok.manager, 'POSITIONS_WITH_BUYER');
      const list = await http('GET', `/api/purchase-positions?query=${encodeURIComponent(`project.id==${w.projA} AND status==WITH_BUYER`)}`, w.tok.manager);
      expect(kpi.rows[0].cnt).toBe(list.body.totalElements);
      expect(kpi.rows[0].cnt).toBe(2);
    } finally {
      await destroyWorld(w);
    }
  });
});

void setStatus;
