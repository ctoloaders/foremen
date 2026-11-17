import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { get, makeWorld, ok, post, psql, type World } from './support.js';
import { consumptionPick, seedFinishingBom, seedWorkStart } from './support.js';
// FOR-11-06 Part A: A5-01, A5-05, A5-06 on a real finishing estimate. The finishing BoM (estimate line, room cell,
// `estimate_line_room_materials`) is seeded by SQL because REST cannot build it; the sync endpoint is called over REST.
// Repeatability: run-id world per test; `makeWorld().cleanup()` in `finally` deletes the positions, the seeded rooms
// (their estimate cells and BoM rows cascade), then the project (estimate and schedule cascade).
const sync = (w: World) => post(`/api/purchase-positions/sync-from-estimate?projectId=${w.projectId}`, w.t('MANAGER'), undefined);
const derived = (w: World): Array<{ id: number; status: string; qty: number; mat: number | null; changed: boolean; removed: boolean; key: string; needBy: string }> =>
  psql(
    `SELECT id, status, quantity, COALESCE(planned_material_id, 0), estimate_changed, estimate_removed, estimate_source_key, COALESCE(need_by_date::text, '')
     FROM purchase_positions WHERE project_id = ${w.projectId} AND estimate_source_key IS NOT NULL ORDER BY id;`,
  )
    .split('\n')
    .filter(Boolean)
    .map((l) => {
      const [id, status, qty, mat, changed, removed, key, needBy] = l.split('|');
      return { id: Number(id), status: status!, qty: Number(qty), mat: Number(mat) || null, changed: changed === 't', removed: removed === 't', key: key!, needBy: needBy! };
    });
/** Two chosen-material rows and one placeholder (type only). */
function seedThree(w: World) {
  const pick = consumptionPick();
  const bom = seedFinishingBom(
    w,
    [
      { kind: 'materialRow', materialId: pick.materialIds[0], roomQty: 10, norm: 1 },
      { kind: 'materialRow', materialId: pick.materialIds[1], roomQty: 10, norm: 1 },
      { kind: 'placeholder', typeId: pick.placeholderTypeId, roomQty: 10, norm: 1 },
    ],
    { workItemId: pick.workItemId },
  );
  return { pick, bom };
}
describe('FOR-11-06 API: positions from a real finishing estimate', { tags: ['for-11-06', 'api', 'estimate-sync'] }, () => {
  test('TC-A5-01 three BoM rows give three PLANNED / AGREED / FINISHING positions: two with a material, one without; a second sync changes nothing', async () => {
    const w = await makeWorld();
    try {
      seedThree(w);
      const first = await sync(w);
      expect(first.status, first.text).toBe(200);
      expect(first.body.created).toBe(3);
      const rows = derived(w);
      expect(rows).toHaveLength(3);
      expect(rows.filter((r) => r.mat !== null)).toHaveLength(2);
      expect(rows.filter((r) => r.mat === null)).toHaveLength(1);
      for (const r of rows) expect(r.status).toBe('PLANNED');
      expect(psql(`SELECT DISTINCT kind || '/' || sourcing_path FROM purchase_positions WHERE project_id = ${w.projectId} AND estimate_source_key IS NOT NULL;`)).toBe('FINISHING/AGREED');
      // TC-A5-02 on the same data: idempotent.
      const second = await sync(w);
      expect(second.status).toBe(200);
      expect(second.body).toEqual(expect.objectContaining({ created: 0, unchanged: 3 }));
      expect(derived(w)).toHaveLength(3);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-05 re-sync rules: PLANNED follows the estimate, PROPOSED keeps its quantity and is flagged, a removed row is flagged and kept', async () => {
    const w = await makeWorld();
    try {
      const { pick, bom } = seedThree(w);
      expect((await sync(w)).body.created).toBe(3);
      const before = derived(w);
      const a = before.find((r) => r.mat === pick.materialIds[0])!;
      const b = before.find((r) => r.mat === pick.materialIds[1])!;
      // B goes to the client's decision: PROPOSED.
      const sent = await post('/api/position-approvals/staff/send', w.t('MANAGER'), { positionIds: [b.id], decisionDeadline: null, managerNote: w.tag('qa06') });
      expect(sent.status, sent.text).toBe(200);
      expect(derived(w).find((r) => r.id === b.id)!.status).toBe('PROPOSED');
      // The estimate doubles the norm of A and B.
      psql(`UPDATE estimate_line_room_materials SET norm_qty = norm_qty * 2 WHERE id IN (${bom.bomIds[0]}, ${bom.bomIds[1]});`);
      const again = await sync(w);
      expect(again.status, again.text).toBe(200);
      const after = derived(w);
      const a2 = after.find((r) => r.id === a.id)!;
      const b2 = after.find((r) => r.id === b.id)!;
      expect(a2.qty).toBeCloseTo(a.qty * 2, 2);
      expect(a2.changed).toBe(false);
      expect(b2.qty).toBeCloseTo(b.qty, 2);
      expect(b2.changed).toBe(true);
      // The row of A disappears from the estimate: the position stays, flagged.
      psql(`DELETE FROM estimate_line_room_materials WHERE id = ${bom.bomIds[0]};`);
      expect((await sync(w)).status).toBe(200);
      const gone = derived(w).find((r) => r.id === a.id);
      expect(gone, 'the position of a removed row is not deleted').toBeTruthy();
      expect(gone!.removed).toBe(true);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A5-06 the position takes the work category and the need-by date from the work schedule', async () => {
    const w = await makeWorld();
    try {
      const { pick } = seedThree(w);
      const start = seedWorkStart(w, pick.categoryId, 6);
      expect((await sync(w)).body.created).toBe(3);
      const rows = derived(w);
      const withMaterial = rows.filter((r) => r.mat !== null);
      for (const r of withMaterial) expect(r.needBy, `need_by_date of position ${r.id}`).toBe(start);
      const planner = await get(`/api/purchase-positions/planner?projectId=${w.projectId}&size=100`, w.t('MANAGER'));
      expect(planner.status, planner.text).toBe(200);
      const list = (planner.body.content ?? planner.body) as any[];
      const row = list.find((x) => x.positionId === withMaterial[0]!.id);
      expect(row?.effectiveWorkCategoryId).toBe(pick.categoryId);
      expect(String(row?.workStartDate)).toBe(start);
    } finally {
      await w.cleanup();
    }
  });
});
void ok;
