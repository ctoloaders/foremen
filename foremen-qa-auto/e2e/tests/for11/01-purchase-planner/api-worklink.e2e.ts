import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  api, buildDataset, C1, C2, C3, CAT, dayShift, plannerRows, setEstimateWorkItems, setScheduleBars, setStatus, setupEnv,
  sql, teardownEnv, WORK_ITEM_C1_NORM, WORK_ITEM_C2_NORM, type Env,
} from './support.js';

// FOR-11-01 Part A: A7 link between positions and the work schedule.
// Catalog data used (read-only): material type 41 ("MT1", materials 569/570) has norms for work items 73 (category 4 = C1)
// and 192 (category 11 = C2); material 61 has a type without norms ("MT9"); material 464 has no type ("NOTYPE").
// Repeatability: project A of this run owns the estimate / schedule rows (SQL fixtures, removed by teardownEnv);
// every test starts from base() and creates its own positions (notes qa-<run-id>-wl<n>).
describe('FOR-11-01 API work-schedule link', { tags: ['for-11-01', 'api'] }, () => {
  let env: Env;
  let seq = 0;
  beforeAll(async () => {
    env = await setupEnv();
    await buildDataset(env); // base data of the run, not used directly here
  });
  afterAll(async () => {
    await teardownEnv(env);
  });
  const t = () => env.tok.manager;
  /** estimate: C1 norm work + C3 work; bars C1 +7, C3 +20 (C2 unscheduled) */
  const base = () => {
    setEstimateWorkItems(env.projectA, [{ id: WORK_ITEM_C1_NORM, unitId: 3 }, { id: 155, unitId: 3 }]);
    setScheduleBars(env.projectA, [{ categoryId: C1, startDay: 7 }, { categoryId: C3, startDay: 20 }]);
  };
  const mk = async (material: number, over: Record<string, unknown> = {}): Promise<number> => {
    const r = await api('POST', '/api/purchase-positions', t(), {
      projectId: env.projectA, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: material, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-wl${++seq}`, ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    return r.body.id;
  };
  const row = async (id: number) =>
    (await plannerRows(t(), `projectId=${env.projectA}`)).find((x) => x.positionId === id);

  test('TC-A7-01 category is resolved automatically through the estimate norm (tier 1)', async () => {
    base();
    const id = await mk(CAT.mt1, { needByDate: dayShift(9) });
    const r = await row(id);
    expect(r.workCategoryId).toBeNull();
    expect(r.effectiveWorkCategoryId).toBe(C1);
    expect(r.workCategorySource).toBe('AUTO');
    expect(r.workStartDate).toBe(dayShift(7));
    expect(r.suggestedNeedByDate).toBe(dayShift(7));
    expect(r.workCategoryCandidates).toEqual([]);
    expect(r.deliveryRisk).toBe(true); // delivery +9 is after work start +7
    const early = await mk(CAT.mt1, { needByDate: dayShift(5) });
    expect((await row(early)).deliveryRisk).toBe(false);
  });

  test('TC-A7-02 tier 2 (catalog) when the estimate has no consuming work; unscheduled C2 is never chosen', async () => {
    setEstimateWorkItems(env.projectA, [{ id: 52, unitId: 3 }, { id: 155, unitId: 3 }]); // work 52 is category C1 but does not consume type 41
    setScheduleBars(env.projectA, [{ categoryId: C1, startDay: 7 }, { categoryId: C3, startDay: 20 }]);
    const id = await mk(CAT.mt1);
    const r = await row(id);
    expect(r.effectiveWorkCategoryId).toBe(C1);
    expect(r.workCategorySource).toBe('AUTO');
    expect(r.effectiveWorkCategoryId).not.toBe(C2);
  });

  test('TC-A7-03 several matches: the earliest start wins and the others are candidates', async () => {
    setEstimateWorkItems(env.projectA, [{ id: WORK_ITEM_C1_NORM, unitId: 3 }, { id: WORK_ITEM_C2_NORM, unitId: 4 }, { id: 155, unitId: 3 }]);
    setScheduleBars(env.projectA, [{ categoryId: C1, startDay: 7 }, { categoryId: C2, startDay: 3 }, { categoryId: C3, startDay: 20 }]);
    const id = await mk(CAT.mt1);
    const r = await row(id);
    expect(r.effectiveWorkCategoryId).toBe(C2);
    expect(r.workStartDate).toBe(dayShift(3));
    expect(r.workCategoryCandidates.map((c: any) => [c.id, c.startDate])).toEqual([[C1, dayShift(7)]]);
  });

  test('TC-A7-03 equal starts are resolved deterministically (by name, then id)', async () => {
    setEstimateWorkItems(env.projectA, [{ id: WORK_ITEM_C1_NORM, unitId: 3 }, { id: WORK_ITEM_C2_NORM, unitId: 4 }]);
    setScheduleBars(env.projectA, [{ categoryId: C1, startDay: 7 }, { categoryId: C2, startDay: 7 }]);
    const id = await mk(CAT.mt1);
    const first = await row(id);
    const second = await row(id);
    expect(first.effectiveWorkCategoryId).toBe(second.effectiveWorkCategoryId);
    expect(first.effectiveWorkCategoryId).toBe(C1); // PL "HYDRAULIKA..." sorts before "STOLARKA"
    expect(first.workCategoryCandidates.length).toBe(1);
  });

  test('TC-A7-04 no type / type without norms gives NONE and no risk', async () => {
    base();
    for (const material of [CAT.noType, CAT.mt9]) {
      const r = await row(await mk(material, { needByDate: dayShift(40) }));
      expect(r.effectiveWorkCategoryId).toBeNull();
      expect(r.workCategorySource).toBe('NONE');
      expect(r.workStartDate).toBeNull();
      expect(r.deliveryRisk).toBe(false);
    }
  });

  test('TC-A7-05 manual category: set, survives a material change, reset by DELETE property', async () => {
    base();
    const id = await mk(CAT.mt1);
    const put = await api('PUT', `/api/purchase-positions/${id}`, t(), { workCategoryId: C3 });
    expect(put.status, JSON.stringify(put.body)).toBe(200);
    expect(put.body.workCategoryId).toBe(C3);
    let r = await row(id);
    expect(r.workCategorySource).toBe('MANUAL');
    expect(r.effectiveWorkCategoryId).toBe(C3);
    expect(r.workStartDate).toBe(dayShift(20));
    const swap = await api('PUT', `/api/purchase-positions/${id}`, t(), { plannedMaterialId: CAT.noType });
    expect(swap.status, JSON.stringify(swap.body)).toBe(200);
    r = await row(id);
    expect(r.workCategorySource).toBe('MANUAL');
    expect(r.effectiveWorkCategoryId).toBe(C3);
    // PUT with null does not clear the field
    const nul = await api('PUT', `/api/purchase-positions/${id}`, t(), { workCategoryId: null });
    expect(nul.status).toBe(200);
    expect((await row(id)).workCategoryId).toBe(C3);
    const del = await api('DELETE', `/api/purchase-positions/${id}/property?properties=workCategory`, t());
    expect(del.status, JSON.stringify(del.body)).toBe(204);
    r = await row(id);
    expect(r.workCategoryId).toBeNull();
    expect(r.workCategorySource).toBe('NONE'); // material has no type now
  });

  test('TC-A7-05 manual category without a schedule bar: MANUAL, no start date, no risk', async () => {
    base();
    const id = await mk(CAT.mt1, { needByDate: dayShift(40) });
    expect((await api('PUT', `/api/purchase-positions/${id}`, t(), { workCategoryId: C2 })).status).toBe(200);
    const r = await row(id);
    expect(r.workCategorySource).toBe('MANUAL');
    expect(r.workStartDate).toBeNull();
    expect(r.deliveryRisk).toBe(false);
  });

  test('TC-A7-05 an unknown category is a 400', async () => {
    base();
    const id = await mk(CAT.mt1);
    const r = await api('PUT', `/api/purchase-positions/${id}`, t(), { workCategoryId: 987654321 });
    expect(r.status, JSON.stringify(r.body)).toBe(400);
  });

  test('TC-A7-05 at stage ORDERED the category change and the reset are rejected', async () => {
    base();
    const id = await mk(CAT.mt1);
    expect((await api('PUT', `/api/purchase-positions/${id}`, t(), { workCategoryId: C3 })).status).toBe(200);
    setStatus(id, 'ORDERED');
    const put = await api('PUT', `/api/purchase-positions/${id}`, t(), { workCategoryId: C1 });
    expect([400, 409], JSON.stringify(put.body)).toContain(put.status);
    const del = await api('DELETE', `/api/purchase-positions/${id}/property?properties=workCategory`, t());
    expect(del.status, JSON.stringify(del.body)).toBe(409);
  });

  test('TC-A7-06 replacing the material re-resolves without persisting; a schedule change moves the start', async () => {
    base();
    const id = await mk(CAT.mt1);
    expect((await row(id)).effectiveWorkCategoryId).toBe(C1);
    const rep = await api('POST', `/api/purchase-positions/${id}/variants/replace`, t(), { materialId: CAT.noType, reason: 'ANALOG' });
    expect(rep.status, JSON.stringify(rep.body)).toBe(200);
    expect((await row(id)).workCategorySource).toBe('NONE');
    expect(sql(`SELECT COALESCE(work_category_id::text,'NULL') FROM purchase_positions WHERE id=${id}`)).toBe('NULL');
    const id2 = await mk(CAT.mt1);
    expect((await row(id2)).workStartDate).toBe(dayShift(7));
    setScheduleBars(env.projectA, [{ categoryId: C1, startDay: 11 }, { categoryId: C3, startDay: 20 }]);
    expect((await row(id2)).workStartDate).toBe(dayShift(11));
  });

  test('TC-A7-07 scheduled categories: ordered by start, filtered by RU/PL name, no wildcard, project scoped', async () => {
    base();
    const r = await api('GET', `/api/purchase-positions/scheduled-categories?projectId=${env.projectA}`, t());
    expect(r.status).toBe(200);
    expect(r.body.map((c: any) => [c.id, c.startDate])).toEqual([[C1, dayShift(7)], [C3, dayShift(20)]]);
    const pl = await api('GET', `/api/purchase-positions/scheduled-categories?projectId=${env.projectA}&q=hydraulika`, t());
    expect(pl.body.map((c: any) => c.id)).toEqual([C1]);
    const ru = await (await fetch(`http://localhost:8080/api/purchase-positions/scheduled-categories?projectId=${env.projectA}&q=малярные`, {
      headers: { Authorization: `Bearer ${t()}`, 'Accept-Language': 'ru' },
    })).json();
    expect(ru.map((c: any) => c.id)).toEqual([C3]);
    for (const wild of ['%25', '_']) {
      const w = await api('GET', `/api/purchase-positions/scheduled-categories?projectId=${env.projectA}&q=${wild}`, t());
      expect(w.body, wild).toEqual([]);
    }
    const foreign = await api('GET', `/api/purchase-positions/scheduled-categories?projectId=${env.projectB}`, t());
    expect([403, 404]).toContain(foreign.status);
  });

  test('TC-A7-07 an orphan bar (category no longer in the estimate) is not offered', async () => {
    setEstimateWorkItems(env.projectA, [{ id: 155, unitId: 3 }]); // C1 work removed, bar stays
    setScheduleBars(env.projectA, [{ categoryId: C1, startDay: 7 }, { categoryId: C3, startDay: 20 }]);
    const r = await api('GET', `/api/purchase-positions/scheduled-categories?projectId=${env.projectA}`, t());
    expect(r.body.map((c: any) => c.id)).toEqual([C3]);
  });

  test('TC-A7-08 work-link preview before the position exists', async () => {
    base();
    const p = await api('GET', `/api/purchase-positions/work-link-preview?projectId=${env.projectA}&materialId=${CAT.mt1}`, t());
    expect(p.status).toBe(200);
    expect(p.body.effectiveWorkCategoryId).toBe(C1);
    expect(p.body.workCategorySource).toBe('AUTO');
    expect(p.body.startDate).toBe(dayShift(7));
    const none = await api('GET', `/api/purchase-positions/work-link-preview?projectId=${env.projectA}&materialId=${CAT.noType}`, t());
    expect(none.body.workCategorySource).toBe('NONE');
    const foreign = await api('GET', `/api/purchase-positions/work-link-preview?projectId=${env.projectB}&materialId=${CAT.mt1}`, t());
    expect([403, 404]).toContain(foreign.status);
  });
});
