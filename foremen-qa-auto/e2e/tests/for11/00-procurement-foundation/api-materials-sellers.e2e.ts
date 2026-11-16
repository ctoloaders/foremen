import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { createWorld, createUser, destroyWorld, genNip, hasMsg, http, MSG, newPosition, newProjectMaterial, newSellerCode, tokenOf, type World } from './support.js';

// FOR-11-00 Part A: A2 (project materials), A3 (seller NIP and lookup), A10 (quick seller create).
// Repeatability: run-id generator (model QA-MAT-<run-id>, seller names/codes carry the run-id, NIPs come from
// genNip()) + teardown in `finally` (destroyWorld removes positions, materials, sellers, projects, users).
const q = (s: string) => encodeURIComponent(s);

/** PUT body for a project material built from its current state. */
async function updateBody(w: World, id: number, over: Record<string, unknown> = {}) {
  const m = (await http('GET', `/api/project-materials/${id}`, w.admin)).body;
  return {
    projectId: m.projectId, categoryId: m.categoryId, materialId: m.materialId, unitId: m.unitId,
    offerPackageIds: m.offerPackageIds, model: m.model, ...over,
  };
}

describe('FOR-11-00 A2 project materials', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A2-01 a MANAGER creates a project material through the API', async ({}) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const sellerCode = newSellerCode(w);
      const seller = await http('POST', '/api/material-sellers', w.admin, { code: sellerCode, nameRU: `QA ${w.id}`, namePL: `QA ${w.id}` });
      expect(seller.status).toBe(200);
      const r = await newProjectMaterial(w, w.projA, `QA-MAT-${w.id}-NEW`, { sellerId: seller.body.id, link: 'https://example.com/x' });
      // ENV-1 (FINDINGS.md, Blocked): the local DB has a NOT NULL finishing_materials.package_volume from another branch's
      // migrations; every finishing-material insert then answers 409 error.data.integrity. Not a main-branch product bug.
      if (r.status === 409 && String(r.body?.message).includes('integralno')) {
        test.skip(true, 'ENV-1: finishing_materials.package_volume NOT NULL drift in the local DB blocks creating materials via API');
      }
      expect([200, 201]).toContain(r.status);
      expect(r.body.projectId).toBe(w.projA);
      expect(r.body.sellerId).toBe(seller.body.id);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A2-02 a project material is hidden from the global finishing-materials list but readable by id', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const model = `QA-MAT-${w.id}-A`;
      const global = await http('GET', `/api/finishing-materials?query=${q(`model==${model}`)}`, w.admin);
      expect(global.status).toBe(200);
      expect(global.body.content).toHaveLength(0);
      const mine = await http('GET', `/api/project-materials?projectId=${w.projA}`, w.tok.manager);
      expect(mine.status).toBe(200);
      expect(mine.body.content.map((m: any) => m.id)).toContain(w.matA);
      const byId = await http('GET', `/api/finishing-materials/${w.matA}`, w.admin);
      expect(byId.status).toBe(200);
      expect(byId.body.id).toBe(w.matA);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A2-03 the project of a project material is immutable', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const r = await http('PUT', `/api/project-materials/${w.matA}`, w.admin, await updateBody(w, w.matA, { projectId: w.projB }));
      expect([400, 409]).toContain(r.status);
      expect(hasMsg(r, MSG.materialProjectImmutable)).toBe(true);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A2-04 a material used by a position cannot be deleted (409 inUse); after the position is gone it can (204)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const pos = await newPosition(w);
      expect(pos.status).toBe(200);
      const blocked = await http('DELETE', `/api/project-materials/${w.matA}`, w.tok.manager);
      expect(blocked.status).toBe(409);
      expect(hasMsg(blocked, MSG.materialInUse)).toBe(true);
      expect((await http('DELETE', `/api/purchase-positions/${pos.body.id}`, w.tok.manager)).status).toBe(204);
      expect((await http('DELETE', `/api/project-materials/${w.matA}`, w.tok.manager)).status).toBe(204);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A2-05 a project material cannot be created in a project that is not ACTIVE (409 notActive)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const r = await newProjectMaterial(w, w.projD, `QA-MAT-${w.id}-D`);
      expect(r.status).toBe(409);
      expect(hasMsg(r, MSG.positionProjectNotActive)).toBe(true);
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A3 seller NIP and lookup', { tags: ['for-11-00', 'api'] }, () => {
  const seller = (w: World, nip?: string, extra: Record<string, unknown> = {}) => {
    const code = newSellerCode(w);
    return { code, nameRU: `QA Sprzedawca ${w.id} ${code.slice(-3)}`, namePL: `QA Sprzedawca ${w.id} ${code.slice(-3)}`, ...(nip ? { nip } : {}), ...extra };
  };

  test('TC-A3-01 a seller with a valid NIP is stored as ten digits', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const nip = genNip();
      const formatted = ` ${nip.slice(0, 3)}-${nip.slice(3, 6)}-${nip.slice(6, 8)}-${nip.slice(8)} `;
      const r = await http('POST', '/api/material-sellers', w.admin, seller(w, formatted));
      expect([200, 201]).toContain(r.status);
      expect(r.body.nip).toBe(nip);
      const back = await http('GET', `/api/material-sellers/${r.body.id}`, w.admin);
      expect(back.body.nip).toBe(nip);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A3-02 an invalid NIP checksum is rejected with 400', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const r = await http('POST', '/api/material-sellers', w.admin, seller(w, '1234567890'));
      expect(r.status).toBe(400);
      expect(hasMsg(r, MSG.nipInvalid)).toBe(true);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A3-03 a duplicate NIP is rejected with 409', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const nip = genNip();
      expect([200, 201]).toContain((await http('POST', '/api/material-sellers', w.admin, seller(w, nip))).status);
      const dup = await http('POST', '/api/material-sellers', w.admin, seller(w, nip));
      expect(dup.status).toBe(409);
      expect(hasMsg(dup, MSG.nipDuplicate)).toBe(true);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A3-04 lookup finds an existing seller in the dictionary (source DICTIONARY)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const nip = genNip();
      const created = await http('POST', '/api/material-sellers', w.admin, seller(w, nip));
      const r = await http('GET', `/api/material-sellers/lookup?nip=${nip}`, w.tok.manager);
      expect(r.status).toBe(200);
      expect(r.body.source).toBe('DICTIONARY');
      expect(r.body.seller.id).toBe(created.body.id);
      expect(r.body.nip).toBe(nip);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A3-05 lookup with the registry disabled answers NONE with registryAvailable=false', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const r = await http('GET', `/api/material-sellers/lookup?nip=${genNip()}`, w.tok.manager);
      expect(r.status).toBe(200);
      expect(r.body.source).toBe('NONE');
      expect(r.body.registryAvailable).toBe(false);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A3-06 lookup rejects an invalid NIP (400) and a caller without MATERIAL_SELLERS/READ (403)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const bad = await http('GET', '/api/material-sellers/lookup?nip=abc', w.tok.manager);
      expect(bad.status).toBe(400);
      expect(hasMsg(bad, MSG.nipInvalid)).toBe(true);
      const client = createUser('CLIENT', 'cl');
      w.extraUsers.push(client);
      const denied = await http('GET', `/api/material-sellers/lookup?nip=${genNip()}`, await tokenOf(client));
      expect(denied.status).toBe(403);
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A10 quick seller creation', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A10-01 quick create by name only', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const name = `QA Sprzedawca ${w.id}`;
      const r = await http('POST', '/api/material-sellers/quick', w.tok.manager, { name });
      expect(r.status).toBe(201);
      expect(r.body.created).toBe(true);
      expect(r.body.seller.name).toBe(name);
      expect(r.body.seller.code).toMatch(/^[a-z0-9_]{1,100}$/);
      expect(r.body.seller.active).toBe(true);
      const ru = await http('GET', `/api/material-sellers/${r.body.seller.id}`, w.admin, undefined, 'ru');
      expect(ru.body.nameRU).toBe(name); // nameRU = namePL = name
      expect(ru.body.namePL).toBe(name);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A10-02 quick create with a NIP; the second call with the same NIP returns the same seller', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const nip = genNip();
      const first = await http('POST', '/api/material-sellers/quick', w.tok.manager, { name: `QA NIP ${w.id}`, nip });
      expect(first.status).toBe(201);
      const again = await http('POST', '/api/material-sellers/quick', w.tok.manager, { name: `QA NIP other ${w.id}`, nip });
      expect(again.status).toBe(200);
      expect(again.body.created).toBe(false);
      expect(again.body.seller.id).toBe(first.body.seller.id);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A10-03 invalid NIP and empty name are 400', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const badNip = await http('POST', '/api/material-sellers/quick', w.tok.manager, { name: `QA ${w.id}`, nip: '1234567890' });
      expect(badNip.status).toBe(400);
      expect(hasMsg(badNip, MSG.nipInvalid)).toBe(true);
      const noName = await http('POST', '/api/material-sellers/quick', w.tok.manager, { name: '' });
      expect(noName.status).toBe(400);
      expect(noName.body.fieldErrors?.name).toBe("error.materialSeller.quick.nameRequired"); // raw key, see FINDINGS.md observation O-1
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A10-04 two sellers with the same name get different codes', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const name = `QA Dup ${w.id}`;
      const a = await http('POST', '/api/material-sellers/quick', w.tok.manager, { name });
      const b = await http('POST', '/api/material-sellers/quick', w.tok.manager, { name });
      expect(a.status).toBe(201);
      expect(b.status).toBe(201);
      expect(a.body.seller.code).not.toBe(b.body.seller.code);
      expect(b.body.seller.code).toMatch(/_2$/);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A10-05 FOREMAN and FINANCIER are denied; BUYER and MANAGER can quick-create', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      expect((await http('POST', '/api/material-sellers/quick', w.tok.foreman, { name: `QA F ${w.id}` })).status).toBe(403);
      expect((await http('POST', '/api/material-sellers/quick', w.tok.financier, { name: `QA Fin ${w.id}` })).status).toBe(403);
      expect((await http('POST', '/api/material-sellers/quick', w.tok.buyer, { name: `QA B ${w.id}` })).status).toBe(201);
      expect((await http('POST', '/api/material-sellers/quick', w.tok.manager, { name: `QA M ${w.id}` })).status).toBe(201);
    } finally {
      await destroyWorld(w);
    }
  });
});
