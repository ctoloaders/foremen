import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { day, errMatches, get, http, makeWorld, ok, post, positionOf, psql, put, type World } from './support.js';
// FOR-11-06 Part A: A5 (positions from the estimate), A6 (materials matrix without finishing), A7 (approval table:
// thumbnail, selection total, bulk actions). Source: test-cases.md of FOR-11-06.
// Repeatability: run-id world per test, cleanup in `finally`.
// Environment note: the finishing estimate (BoM rows with a source key) cannot be built through REST, so the
// positions that the sync would create are inserted by SQL with an `estimate_source_key` (exactly what the sync
// writes); the sync endpoint itself is exercised on the empty estimate (summary, idempotency, rights, status).
/** Inserts an estimate-derived position with no chosen material (what the sync creates for a placeholder row). */
function placeholderPosition(w: World, key: string): number {
  return Number(
    psql(
      `INSERT INTO purchase_positions (project_id, kind, sourcing_path, status, planned_material_id, quantity, unit_id, estimate_source_key, created_by)
       VALUES (:'pid'::bigint, 'FINISHING', 'AGREED', 'PLANNED', NULL, 5, 1, :'key', 'qa') RETURNING id;`,
      { pid: String(w.projectId), key },
    ).split('\n')[0],
  );
}
/** True when the SQL statement is rejected (DB guard). */
const fails = (fn: () => unknown): boolean => {
  try {
    fn();
    return false;
  } catch {
    return true;
  }
};
const sync = (w: World, tag: 'MANAGER' | 'ADMIN' | 'BUYER' | 'FOREMAN' | 'CLIENT', projectId = w.projectId) =>
  post(`/api/purchase-positions/sync-from-estimate?projectId=${projectId}`, w.t(tag), undefined);
const staff = async (w: World) => (await ok('GET', `/api/position-approvals/staff/projects/${w.projectId}`, w.t('MANAGER'))) as any[];
describe('FOR-11-06 API: estimate sync, matrix, approval table', { tags: ['for-11-06', 'api'] }, () => {
  test('TC-A5-01 and A5-02 sync answers a summary and is idempotent', async () => {
    const w = await makeWorld();
    try {
      const first = await sync(w, 'MANAGER');
      expect(first.status, first.text).toBe(200);
      expect(first.body).toEqual(expect.objectContaining({ created: expect.any(Number), updated: expect.any(Number), unchanged: expect.any(Number), flagged: expect.any(Number) }));
      const count = () => Number(psql(`SELECT COUNT(*) FROM purchase_positions WHERE project_id = ${w.projectId} AND estimate_source_key IS NOT NULL;`));
      const n1 = count();
      expect(n1).toBe(first.body.created);
      const second = await sync(w, 'MANAGER');
      expect(second.status).toBe(200);
      expect(second.body.created).toBe(0);
      expect(count()).toBe(n1);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-03 a position without a material cannot be sent for approval (409 materialRequired)', async () => {
    const w = await makeWorld();
    try {
      const p = placeholderPosition(w, `qa06-${w.id}-a`);
      const r = await post('/api/position-approvals/staff/send', w.t('MANAGER'), { positionIds: [p], decisionDeadline: null, managerNote: w.tag('rcpt') });
      expect(errMatches(r, 409, 'error.estimateSync.materialRequired')).toBeNull();
      const row = (await staff(w)).find((x) => x.positionId === p);
      expect(row?.fromEstimate).toBe(true);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-04 the material can be chosen while PLANNED', async () => {
    const w = await makeWorld();
    try {
      const p = placeholderPosition(w, `qa06-${w.id}-b`);
      const r = await put(`/api/purchase-positions/${p}`, w.t('MANAGER'), { plannedMaterialId: w.materialId });
      expect(r.status, r.text).toBe(200);
      expect((await positionOf(w, p)).plannedMaterialId).toBe(w.materialId);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-07 sync of a project that is not ACTIVE answers 409 notActive', async () => {
    const w = await makeWorld();
    try {
      psql(`UPDATE projects SET status = 'DRAFT' WHERE id = ${w.projectId};`);
      expect(errMatches(await sync(w, 'MANAGER'), 409, 'error.purchasePosition.project.notActive')).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-09 DB guards: a non-PLANNED position without material and a duplicate source key are rejected', async () => {
    const w = await makeWorld();
    try {
      const insert = (status: string, key: string, mat: string) =>
        psql(
          `INSERT INTO purchase_positions (project_id, kind, sourcing_path, status, planned_material_id, quantity, unit_id, estimate_source_key, created_by)
           VALUES (:'pid'::bigint, 'FINISHING', 'AGREED', :'st', ${mat}, 1, 1, :'key', 'qa');`,
          { pid: String(w.projectId), st: status, key },
        );
      expect(fails(() => insert('PROPOSED', `qa06-${w.id}-c`, 'NULL'))).toBe(true);
      placeholderPosition(w, `qa06-${w.id}-d`);
      expect(fails(() => insert('PLANNED', `qa06-${w.id}-d`, 'NULL'))).toBe(true);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-10 rights: FOREMAN and CLIENT 403, BUYER and MANAGER 200', async () => {
    const w = await makeWorld();
    try {
      expect((await sync(w, 'FOREMAN')).status).toBe(403);
      expect((await sync(w, 'CLIENT')).status).toBe(403);
      expect((await sync(w, 'BUYER')).status).toBe(200);
      expect((await sync(w, 'MANAGER')).status).toBe(200);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-08 activating a DRAFT project does not fail because of the estimate sync', async () => {
    const w = await makeWorld();
    try {
      const created = await ok('POST', '/api/projects', w.admin, { name: `FOR1106-ACT-${w.id}`, status: 'DRAFT', members: [] });
      try {
        const r = await http('PUT', `/api/projects/${created.id}`, w.admin, { ...created, status: 'ACTIVE' });
        // Activation may be refused for unrelated readiness reasons (rooms/estimate); it must never be refused by the sync.
        expect(String(r.body?.message ?? '')).not.toMatch(/kosztorys/i);
      } finally {
        await http('DELETE', `/api/projects/${created.id}`, w.admin);
      }
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A6-01 the materials matrix has no finishing branch', async () => {
    const w = await makeWorld();
    try {
      const r = await get(`/api/projects/${w.projectId}/materials-matrix`, w.t('MANAGER'));
      expect(r.status, r.text).toBe(200);
      const branches = (r.body.branches ?? []) as Array<{ branch?: string; key?: string }>;
      expect(JSON.stringify(branches)).not.toMatch(/finishing/i);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A6-03 the matrix keeps the PURCHASES READ gate', async () => {
    const w = await makeWorld();
    try {
      expect((await get(`/api/projects/${w.projectId}/materials-matrix`, w.t('CLIENT'))).status).toBe(403);
      expect((await get(`/api/projects/${w.projectId}/materials-matrix`)).status).toBeGreaterThanOrEqual(401);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A7-01 the staff view carries thumbnailUrl (CDN URL or null) and the estimate flags', async () => {
    const w = await makeWorld();
    try {
      const p = await w.plannedPosition();
      const row = (await staff(w)).find((x) => x.positionId === p)!;
      expect(row).toBeDefined();
      expect(row).toHaveProperty('thumbnailUrl');
      if (row.thumbnailUrl !== null) expect(row.thumbnailUrl).toMatch(/^https?:\/\//);
      expect(row).toMatchObject({ fromEstimate: false, estimateChanged: false, estimateRemoved: false });
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A7-02 selection total: count, unpriced, sums', async () => {
    const w = await makeWorld();
    try {
      const a = await w.acceptedPosition(2, 100);
      const b = await w.acceptedPosition(1, 50);
      const r = await post('/api/purchase-positions/selection-total', w.t('MANAGER'), { positionIds: [a, b] });
      expect(r.status, r.text).toBe(200);
      expect(r.body.count).toBe(2);
      expect(r.body.unpriced).toBe(0);
      expect(Number(r.body.saleNet)).toBeCloseTo(250, 2);
      expect(Number(r.body.saleGross)).toBeCloseTo(270, 2);
      const planned = await w.plannedPosition({ sale: null });
      const missing = 999999999;
      const mixed = await post('/api/purchase-positions/selection-total', w.t('MANAGER'), { positionIds: [a, planned, missing] });
      expect(mixed.status, mixed.text).toBe(200);
      // A position without a sale price is counted as unpriced; only unknown ids are skipped (NOT_FOUND).
      expect(mixed.body.count).toBe(2);
      expect(mixed.body.unpriced).toBe(1);
      expect(mixed.body.skipped).toEqual([{ positionId: missing, reason: 'NOT_FOUND' }]);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A7-03 bulk accept on behalf: partial success is reported per position', async () => {
    const w = await makeWorld();
    try {
      const ids: number[] = [];
      for (let i = 0; i < 3; i++) ids.push(await w.plannedPosition({ qty: 2, sale: 100 + i }));
      await ok('POST', '/api/position-approvals/staff/send', w.t('MANAGER'), { positionIds: ids, decisionDeadline: null, managerNote: w.tag('rcpt') });
      const items = [];
      for (const id of ids) {
        const round = (await ok('GET', `/api/position-approvals/staff/positions/${id}/rounds`, w.admin))[0];
        items.push({ positionId: id, optionId: round.options[0].optionId, expectedUnitSalePrice: round.options[0].unitSalePrice, comment: w.tag("rcpt") });
      }
      items[2]!.expectedUnitSalePrice = Number(items[2]!.expectedUnitSalePrice) + 1; // stale price
      const r = await post('/api/position-approvals/staff/rounds/decide-on-behalf-bulk', w.t('MANAGER'), { items });
      expect(r.status, r.text).toBe(200);
      expect(r.body.succeeded, r.text).toEqual([ids[0], ids[1]]);
      expect(r.body.failed).toHaveLength(1);
      expect(r.body.failed[0].positionId).toBe(ids[2]);
      expect(r.body.failed[0].errorKey).toBeTruthy();
      expect((await positionOf(w, ids[0]!)).status).toBe('ACCEPTED');
      expect((await positionOf(w, ids[2]!)).status).toBe('PROPOSED');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A7-04 bulk withdraw closes the rounds; a position without an open round is a failure entry', async () => {
    const w = await makeWorld();
    try {
      const a = await w.plannedPosition();
      const b = await w.plannedPosition();
      await ok('POST', '/api/position-approvals/staff/send', w.t('MANAGER'), { positionIds: [a, b], decisionDeadline: null, managerNote: w.tag('rcpt') });
      const roundOf = async (id: number) => (await ok('GET', `/api/position-approvals/staff/positions/${id}/rounds`, w.admin))[0].roundId as number;
      const ra = await roundOf(a);
      const rb = await roundOf(b);
      const r = await post('/api/position-approvals/staff/rounds/withdraw-bulk', w.t('MANAGER'), { items: [{ roundId: ra }, { roundId: rb }] });
      expect(r.status, r.text).toBe(200);
      expect(r.body.succeeded.sort()).toEqual([a, b].sort());
      expect((await positionOf(w, a)).status).toBe('PLANNED');
      const again = await post('/api/position-approvals/staff/rounds/withdraw-bulk', w.t('MANAGER'), { items: [{ roundId: ra }] });
      expect(again.status).toBe(200);
      expect(again.body.failed).toHaveLength(1);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A7-05 more than 200 items answer 400 tooMany', async () => {
    const w = await makeWorld();
    try {
      const items = Array.from({ length: 201 }, (_, i) => ({ positionId: i + 1 }));
      const r = await post('/api/position-approvals/staff/rounds/decide-on-behalf-bulk', w.t('MANAGER'), { items });
      expect(errMatches(r, 400, 'error.positionApproval.bulk.tooMany')).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A7-06 bulk endpoints are staff only', async () => {
    const w = await makeWorld();
    try {
      for (const tag of ['FOREMAN', 'CLIENT'] as const) {
        expect((await post('/api/position-approvals/staff/rounds/decide-on-behalf-bulk', w.t(tag), { items: [{ positionId: 1 }] })).status, tag).toBe(403);
        expect((await post('/api/position-approvals/staff/rounds/withdraw-bulk', w.t(tag), { items: [{ roundId: 1 }] })).status, tag).toBe(403);
      }
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A7-07 mass send and a proforma from several positions go in one request each', async () => {
    const w = await makeWorld();
    try {
      const a = await w.plannedPosition();
      const b = await w.plannedPosition();
      const send = await post('/api/position-approvals/staff/send', w.t('MANAGER'), { positionIds: [a, b], decisionDeadline: null, managerNote: w.tag('rcpt') });
      expect(send.status, send.text).toBe(200);
      expect((await positionOf(w, a)).status).toBe('PROPOSED');
      expect((await positionOf(w, b)).status).toBe('PROPOSED');
      const x = await w.acceptedPosition(1, 10);
      const y = await w.acceptedPosition(1, 20);
      const draft = await post('/api/client-payments/materials', w.t('MANAGER'), { projectId: w.projectId, positionIds: [x, y], dueDate: day(5) });
      expect(draft.status, draft.text).toBe(200);
    } finally {
      await w.cleanup();
    }
  });
});
