import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { createWorld, destroyWorld, hasMsg, http, isoDate, MSG, newPosition, seedProjectMaterial, setStatus, type World } from './support.js';

// FOR-11-00 Part A: A4 (positions), A5 (lifecycle and the proforma gate), A6 (price visibility).
// Repeatability: run-id generator + teardown (destroyWorld deletes positions/requests/materials/projects/users).
const q = (s: string) => encodeURIComponent(s);
const MONEY = ['plannedPurchasePrice', 'actualPurchasePrice', 'salePrice', 'purchaseVatRateId', 'saleVatRateId', 'deliveryCostShare'];

describe('FOR-11-00 A4 purchase positions', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A4-01 a MANAGER creates a position: PLANNED, unit from the material, VAT 23/8, counters 0', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const matUnit = (await http('GET', `/api/project-materials/${w.matA}`, w.admin)).body.unitId;
      const vats = (await http('GET', '/api/vat-rates?size=50', w.admin)).body.content as Array<{ id: number; rate: number }>;
      const r = await newPosition(w, { needByDate: isoDate(10) });
      expect([200, 201]).toContain(r.status);
      expect(r.body.status).toBe('PLANNED');
      expect(r.body.unitId).toBe(matUnit);
      expect(r.body.purchaseVatRateId).toBe(vats.find((v) => Number(v.rate) === 23)!.id);
      expect(r.body.saleVatRateId).toBe(vats.find((v) => Number(v.rate) === 8)!.id);
      expect(Number(r.body.orderedQuantity)).toBe(0);
      expect(Number(r.body.acceptedQuantity)).toBe(0);
      expect(r.body.projectId).toBe(w.projA);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A4-02 creation validations: quantity, kind, foreign-project material, DRAFT project, negative price', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const zero = await newPosition(w, { quantity: 0 });
      expect(zero.status).toBe(400);
      const kind = await newPosition(w, { kind: 'CONSTRUCTION' });
      expect([400, 409]).toContain(kind.status);
      expect(hasMsg(kind, MSG.kindNotSupported)).toBe(true);
      const foreign = await newPosition(w, { plannedMaterialId: w.matB });
      expect([400, 409]).toContain(foreign.status);
      expect(hasMsg(foreign, MSG.materialProjectMismatch)).toBe(true);
      const draft = await newPosition(w, { projectId: w.projD }, w.admin);
      expect(draft.status).toBe(409);
      expect(hasMsg(draft, MSG.positionProjectNotActive)).toBe(true);
      const neg = await newPosition(w, { plannedPurchasePrice: -1 });
      expect(neg.status).toBe(400);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A4-03 edit restrictions: system fields rejected, quantity editable in PLANNED only, actual price only from WITH_BUYER', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w);
      for (const body of [{ status: 'CANCELLED' }, { orderedQuantity: 5 }, { deliveryCostShare: 10 }]) {
        const r = await http('PUT', `/api/purchase-positions/${p.body.id}`, w.tok.manager, body);
        expect(r.status, JSON.stringify(body)).toBe(400);
        expect(hasMsg(r, MSG.readOnly)).toBe(true);
      }
      const ok = await http('PUT', `/api/purchase-positions/${p.body.id}`, w.tok.manager, { quantity: 7 });
      expect(ok.status).toBe(200);
      expect(Number(ok.body.quantity)).toBe(7);
      const early = await http('PUT', `/api/purchase-positions/${p.body.id}`, w.tok.manager, { actualPurchasePrice: 5 });
      expect([400, 409]).toContain(early.status);
      // a PACKAGE position goes straight to WITH_BUYER; the quantity is then locked
      const pk = await newPosition(w, { sourcingPath: 'PACKAGE' });
      expect((await http('POST', `/api/purchase-positions/${pk.body.id}/transition`, w.tok.manager, { target: 'WITH_BUYER' })).status).toBe(200);
      const locked = await http('PUT', `/api/purchase-positions/${pk.body.id}`, w.tok.manager, { quantity: 9 });
      expect([400, 409]).toContain(locked.status);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A4-04 delete: PLANNED 204, WITH_BUYER 409, BUYER 403', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const planned = await newPosition(w);
      const buyerDel = await http('DELETE', `/api/purchase-positions/${planned.body.id}`, w.tok.buyer);
      expect(buyerDel.status).toBe(403);
      expect((await http('DELETE', `/api/purchase-positions/${planned.body.id}`, w.tok.manager)).status).toBe(204);
      const pk = await newPosition(w, { sourcingPath: 'PACKAGE' });
      await http('POST', `/api/purchase-positions/${pk.body.id}/transition`, w.tok.manager, { target: 'WITH_BUYER' });
      const blocked = await http('DELETE', `/api/purchase-positions/${pk.body.id}`, w.tok.manager);
      expect(blocked.status).toBe(409);
      expect(hasMsg(blocked, MSG.deleteNotAllowed)).toBe(true);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A4-05 filters by status / sourcingPath / needByDate and sort by needByDate with empty dates last', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const d1 = isoDate(5);
      const d2 = isoDate(1);
      const a = await newPosition(w, { needByDate: d1 });
      const b = await newPosition(w, { needByDate: d2, sourcingPath: 'PACKAGE' });
      const c = await newPosition(w);
      await http('POST', `/api/purchase-positions/${b.body.id}/transition`, w.tok.manager, { target: 'WITH_BUYER' });
      const base = `project.id==${w.projA}`;
      const list = (extra: string, sort = '') => http('GET', `/api/purchase-positions?query=${q(`${base}${extra}`)}&size=100${sort}`, w!.tok.manager);
      const ids = (r: any) => r.body.content.map((p: any) => p.id);
      expect(ids(await list(' AND status==WITH_BUYER'))).toEqual([b.body.id]);
      expect(ids(await list(' AND status==PLANNED')).sort()).toEqual([a.body.id, c.body.id].sort());
      expect(ids(await list(' AND sourcingPath==PACKAGE'))).toEqual([b.body.id]);
      expect(ids(await list(` AND needByDate==${d1}`))).toEqual([a.body.id]);
      const sorted = await list('', '&sort=needByDate,asc');
      expect(sorted.status).toBe(200);
      expect(ids(sorted)).toEqual([b.body.id, a.body.id, c.body.id]);
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A5 lifecycle and the proforma-paid gate', { tags: ['for-11-00', 'api'] }, () => {
  const tr = (w: World, id: number, target: string, token?: string) =>
    http('POST', `/api/purchase-positions/${id}/transition`, token ?? w.tok.manager, { target });

  test('TC-A5-01 an AGREED position cannot be sent to the buyer by hand (409, status unchanged)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w);
      const r = await tr(w, p.body.id, 'WITH_BUYER');
      expect(r.status).toBe(409);
      expect(hasMsg(r, MSG.gateViolation) || hasMsg(r, MSG.transitionNotAllowed)).toBe(true);
      expect((await http('GET', `/api/purchase-positions/${p.body.id}`, w.tok.manager)).body.status).toBe('PLANNED');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A5-02 a PACKAGE position goes straight to WITH_BUYER', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w, { sourcingPath: 'PACKAGE' });
      const r = await tr(w, p.body.id, 'WITH_BUYER');
      expect(r.status).toBe(200);
      expect(r.body.status).toBe('WITH_BUYER');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A5-03 a CLIENT_OWN position goes straight to IN_DELIVERY', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w, { sourcingPath: 'CLIENT_OWN' });
      const r = await tr(w, p.body.id, 'IN_DELIVERY');
      expect(r.status).toBe(200);
      expect(r.body.status).toBe('IN_DELIVERY');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A5-04 cancel and reopen', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w);
      const c = await tr(w, p.body.id, 'CANCELLED');
      expect(c.status).toBe(200);
      expect(c.body.status).toBe('CANCELLED');
      const o = await tr(w, p.body.id, 'PLANNED');
      expect(o.status).toBe(200);
      expect(o.body.status).toBe('PLANNED');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A5-05 system stages are not reachable over REST; FOREMAN cannot transition at all', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w);
      for (const target of ['PROPOSED', 'ACCEPTED', 'IN_PROFORMA', 'CLIENT_PAID', 'ORDERED']) {
        const r = await tr(w, p.body.id, target);
        expect(r.status, target).toBe(409);
      }
      expect((await tr(w, p.body.id, 'CANCELLED', w.tok.foreman)).status).toBe(403);
      expect((await http('GET', `/api/purchase-positions/${p.body.id}`, w.tok.manager)).body.status).toBe('PLANNED');
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A6 price visibility', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A6-01 money fields are visible to ADMIN/MANAGER/BUYER and null for FOREMAN', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w, { plannedPurchasePrice: 40, salePrice: 55 });
      expect(p.status).toBe(200);
      for (const tok of [w.tok.admin, w.tok.manager, w.tok.buyer]) {
        const r = await http('GET', `/api/purchase-positions/${p.body.id}`, tok);
        expect(r.status).toBe(200);
        expect(Number(r.body.plannedPurchasePrice)).toBe(40);
        expect(Number(r.body.salePrice)).toBe(55);
        expect(r.body.purchaseVatRateId).not.toBeNull();
      }
      const f = await http('GET', `/api/purchase-positions/${p.body.id}`, w.tok.foreman);
      expect(f.status).toBe(200);
      for (const k of MONEY) expect(f.body[k], k).toBeNull();
      const list = await http('GET', `/api/purchase-positions?query=${q(`project.id==${w.projA}`)}`, w.tok.foreman);
      for (const k of MONEY) expect(list.body.content[0][k], `list ${k}`).toBeNull();
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A6-02 FOREMAN cannot filter or sort by a money field (400); MANAGER can', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await newPosition(w, { plannedPurchasePrice: 40, salePrice: 55 });
      const filter = await http('GET', `/api/purchase-positions?query=${q('salePrice=gt=0')}`, w.tok.foreman);
      expect(filter.status).toBe(400);
      expect(hasMsg(filter, MSG.forbiddenField)).toBe(true);
      const sort = await http('GET', '/api/purchase-positions?sort=plannedPurchasePrice,asc', w.tok.foreman);
      expect(sort.status).toBe(400);
      expect(hasMsg(sort, MSG.forbiddenField)).toBe(true);
      expect((await http('GET', `/api/purchase-positions?query=${q('salePrice=gt=0')}`, w.tok.manager)).status).toBe(200);
      expect((await http('GET', '/api/purchase-positions?sort=plannedPurchasePrice,asc', w.tok.manager)).status).toBe(200);
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A11 variant history', { tags: ['for-11-00', 'api'] }, () => {
  const replace = (w: World, id: number, body: Record<string, unknown>, token?: string) =>
    http('POST', `/api/purchase-positions/${id}/variants/replace`, token ?? w.tok.manager, body);
  const pos = async (w: World, id: number, token?: string) => (await http('GET', `/api/purchase-positions/${id}`, token ?? w.tok.manager)).body;

  test('TC-A11-01 replacing a variant (ANALOG) moves the old one to history', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const m2 = await seedProjectMaterial(w, w.projA, `QA-MAT-${w.id}-M2`);
      const p = await newPosition(w, { salePrice: 100 });
      const r = await replace(w, p.body.id, { materialId: m2, salePrice: 120, reason: 'ANALOG', note: 'qa' });
      expect(r.status).toBe(200);
      const now = await pos(w, p.body.id);
      expect(now.plannedMaterialId).toBe(m2);
      expect(Number(now.salePrice)).toBe(120);
      expect(now.variantCount).toBe(1);
      const hist = await http('GET', `/api/purchase-positions/${p.body.id}/variants`, w.tok.manager);
      expect(hist.body).toHaveLength(1);
      expect(hist.body[0].materialId).toBe(w.matA);
      expect(Number(hist.body[0].unitSalePrice)).toBe(100);
      expect(hist.body[0].reason).toBe('ANALOG');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A11-02 restoring a known variant swaps it back', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const m2 = await seedProjectMaterial(w, w.projA, `QA-MAT-${w.id}-M2`);
      const p = await newPosition(w, { salePrice: 100 });
      await replace(w, p.body.id, { materialId: m2, salePrice: 120, reason: 'ANALOG' });
      const vid = (await http('GET', `/api/purchase-positions/${p.body.id}/variants`, w.tok.manager)).body[0].id;
      const r = await http('POST', `/api/purchase-positions/${p.body.id}/variants/${vid}/restore`, w.tok.manager);
      expect(r.status).toBe(200);
      const now = await pos(w, p.body.id);
      expect(now.plannedMaterialId).toBe(w.matA);
      expect(Number(now.salePrice)).toBe(100);
      expect(now.variantCount).toBe(1);
      const hist = (await http('GET', `/api/purchase-positions/${p.body.id}/variants`, w.tok.manager)).body;
      expect(hist).toHaveLength(1);
      expect(hist[0].materialId).toBe(m2);
      expect(Number(hist[0].unitSalePrice)).toBe(120);
      expect(hist[0].reason).toBe('MANUAL');
      expect(hist.map((h: any) => h.id)).not.toContain(vid);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A11-03 suggestions come from this project only and exclude the current material', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const m3 = await seedProjectMaterial(w, w.projA, `QA-MAT-${w.id}-M3`);
      const p1 = await newPosition(w, { salePrice: 100 });
      await newPosition(w, { plannedMaterialId: m3, salePrice: 77 }); // another position of the project
      await newPosition(w, { projectId: w.projB, plannedMaterialId: w.matB, salePrice: 55 }, w.admin); // other project
      const r = await http('GET', `/api/purchase-positions/${p1.body.id}/variants/suggestions?limit=5`, w.tok.manager);
      expect(r.status).toBe(200);
      const mats = r.body.map((s: any) => s.materialId);
      expect(mats).toContain(m3);
      expect(mats).not.toContain(w.matA);
      expect(mats).not.toContain(w.matB);
      expect(new Set(mats).size).toBe(mats.length);
      expect(Number(r.body.find((s: any) => s.materialId === m3).salePrice)).toBe(77);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A11-04 replace is refused after WITH_BUYER (stageNotAllowed) and for an identical variant (sameAsCurrent)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const m2 = await seedProjectMaterial(w, w.projA, `QA-MAT-${w.id}-M2`);
      const pk = await newPosition(w, { sourcingPath: 'PACKAGE', salePrice: 100 });
      await http('POST', `/api/purchase-positions/${pk.body.id}/transition`, w.tok.manager, { target: 'WITH_BUYER' });
      const late = await replace(w, pk.body.id, { materialId: m2, salePrice: 120, reason: 'ANALOG' });
      expect(late.status).toBe(409);
      expect(hasMsg(late, MSG.variantStage)).toBe(true);
      const p = await newPosition(w, { salePrice: 100 });
      const same = await replace(w, p.body.id, { materialId: w.matA, salePrice: 100, reason: 'ANALOG' });
      expect(same.status).toBe(409);
      expect(hasMsg(same, MSG.variantSame)).toBe(true);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A11-05 FOREMAN sees variants without prices and cannot replace; BUYER can replace in PLANNED', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const m2 = await seedProjectMaterial(w, w.projA, `QA-MAT-${w.id}-M2`);
      const p = await newPosition(w, { salePrice: 100, plannedPurchasePrice: 60 });
      expect((await replace(w, p.body.id, { materialId: m2, salePrice: 120, reason: 'ANALOG' }, w.tok.foreman)).status).toBe(403);
      expect((await replace(w, p.body.id, { materialId: m2, salePrice: 120, reason: 'ANALOG' }, w.tok.buyer)).status).toBe(200);
      const hist = await http('GET', `/api/purchase-positions/${p.body.id}/variants`, w.tok.foreman);
      expect(hist.status).toBe(200);
      expect(hist.body[0].materialId).toBe(w.matA);
      expect(hist.body[0].unitSalePrice).toBeNull();
      expect(hist.body[0].unitPlannedPurchasePrice).toBeNull();
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A11-06 a material that is only in history is protected; deleting the position cascades the history', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const m2 = await seedProjectMaterial(w, w.projA, `QA-MAT-${w.id}-M2`);
      const p = await newPosition(w, { salePrice: 100 });
      await replace(w, p.body.id, { materialId: m2, salePrice: 120, reason: 'ANALOG' });
      const blocked = await http('DELETE', `/api/project-materials/${w.matA}`, w.tok.manager);
      expect(blocked.status).toBe(409);
      expect(hasMsg(blocked, MSG.materialInUse)).toBe(true);
      expect((await http('DELETE', `/api/purchase-positions/${p.body.id}`, w.tok.manager)).status).toBe(204);
      expect((await http('DELETE', `/api/project-materials/${w.matA}`, w.tok.manager)).status).toBe(204);
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A12 hide delivered (positions)', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A12-01 hideDelivered=true drops RECEIVED positions only', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const seeded: Record<string, number> = {};
      for (const st of ['PLANNED', 'IN_DELIVERY', 'PARTIALLY_RECEIVED', 'RECEIVED']) {
        const p = await newPosition(w);
        seeded[st] = p.body.id;
        if (st !== 'PLANNED') setStatus(p.body.id, st); // system stages are seeded by SQL (not reachable over REST)
      }
      const base = `/api/purchase-positions?query=${q(`project.id==${w.projA}`)}&size=100`;
      const all = await http('GET', base, w.tok.manager);
      expect(all.body.content.map((p: any) => p.id).sort()).toEqual(Object.values(seeded).sort());
      const hidden = await http('GET', `${base}&hideDelivered=true`, w.tok.manager);
      expect(hidden.body.content.map((p: any) => p.id).sort()).toEqual([seeded.PLANNED, seeded.IN_DELIVERY, seeded.PARTIALLY_RECEIVED].sort());
      expect(hidden.body.totalElements).toBe(3);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A12-02 a position returned from RECEIVED to PARTIALLY_RECEIVED is listed again', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const p = await newPosition(w);
      setStatus(p.body.id, 'RECEIVED');
      const url = `/api/purchase-positions?query=${q(`project.id==${w.projA}`)}&hideDelivered=true`;
      expect((await http('GET', url, w.tok.manager)).body.content).toHaveLength(0);
      setStatus(p.body.id, 'PARTIALLY_RECEIVED');
      expect((await http('GET', url, w.tok.manager)).body.content.map((x: any) => x.id)).toEqual([p.body.id]);
    } finally {
      await destroyWorld(w);
    }
  });
});
