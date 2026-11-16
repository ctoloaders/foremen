import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { createWorld, destroyWorld, http, newPosition, psql, type World } from './support.js';

// FOR-11-00 Part A, A1 (BUYER role and access) and A9 (stage dictionary, matrix, seeds).
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-00-procurement-foundation/test-cases.md.
// Repeatability: run-id generator + teardown (support.ts createWorld/destroyWorld in `finally`).
const q = (s: string) => encodeURIComponent(s);

describe('FOR-11-00 A1 BUYER role and access', { tags: ['for-11-00', 'api'] }, () => {
  test('TC-A1-01 the BUYER role exists (RU/PL names) and a BUYER user can log in', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const pl = await http('GET', `/api/roles?query=${q('code==BUYER')}`, w.admin);
      expect(pl.status).toBe(200);
      expect(pl.body.content[0].code).toBe('BUYER');
      expect(pl.body.content[0].name).toBe('Specjalista ds. zakupów');
      expect(pl.body.content[0].system).toBe(true);
      const ru = await http('GET', `/api/roles?query=${q('code==BUYER')}`, w.admin, undefined, 'ru');
      expect(ru.body.content[0].name).toBe('Закупщик');
      // the BUYER user (created with role BUYER) holds a working token
      const me = await http('GET', '/api/procurement/projects', w.tok.buyer);
      expect(me.status).toBe(200);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A1-02 BUYER sees procurement data of all projects without membership; MANAGER only his own', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const pa = await newPosition(w);
      const pb = await newPosition(w, { projectId: w.projB, plannedMaterialId: w.matB }, w.admin);
      expect(pa.status).toBe(200);
      expect(pb.status).toBe(200);
      const all = await http('GET', '/api/purchase-positions?size=1000', w.tok.buyer);
      expect(all.status).toBe(200);
      const ids = all.body.content.map((p: any) => p.id);
      expect(ids).toContain(pa.body.id);
      expect(ids).toContain(pb.body.id);
      const onlyA = await http('GET', `/api/purchase-positions?query=${q(`project.id==${w.projA}`)}&size=1000`, w.tok.buyer);
      expect(onlyA.body.content.map((p: any) => p.id)).toEqual([pa.body.id]);
      const projects = await http('GET', '/api/procurement/projects', w.tok.buyer);
      expect(projects.status).toBe(200);
      const pids = projects.body.map((p: any) => p.id);
      expect(pids).toContain(w.projA);
      expect(pids).toContain(w.projB);
      expect(pids).not.toContain(w.projD); // DRAFT
      // the project MANAGER sees only his own project
      const mgr = await http('GET', '/api/purchase-positions?size=1000', w.tok.manager);
      const mids = mgr.body.content.map((p: any) => p.id);
      expect(mids).toContain(pa.body.id);
      expect(mids).not.toContain(pb.body.id);
      const mp = (await http('GET', '/api/procurement/projects', w.tok.manager)).body.map((p: any) => p.id);
      expect(mp).toContain(w.projA);
      expect(mp).not.toContain(w.projB);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-X-01 (extra, R9.9) a project member cannot read or edit a position of another project', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const pb = await newPosition(w, { projectId: w.projB, plannedMaterialId: w.matB }, w.admin);
      const get = await http('GET', `/api/purchase-positions/${pb.body.id}`, w.tok.foreman);
      expect([403, 404]).toContain(get.status);
      const put = await http('PUT', `/api/purchase-positions/${pb.body.id}`, w.tok.manager, { notes: 'x' });
      expect([403, 404]).toContain(put.status);
      const list = await http('GET', '/api/purchase-positions?size=1000', w.tok.foreman);
      expect(list.body.content.map((p: any) => p.id)).not.toContain(pb.body.id);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A1-03 BUYER has no access outside procurement (403)', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const paths = ['/api/bank/connections', '/api/payroll/company', `/api/payments/projects/${w.projA}/schedule`, '/api/estimates', '/api/users', '/api/projects'];
      for (const p of paths) {
        const r = await http('GET', p, w.tok.buyer);
        expect(r.status, `GET ${p}`).toBe(403);
      }
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A1-04 BUYER reads reference dictionaries but cannot write them', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      for (const p of ['finishing-materials', 'material-sellers', 'delivery-categories', 'delivery-statuses', 'vat-rates', 'measurement-units']) {
        const r = await http('GET', `/api/${p}?size=1`, w.tok.buyer);
        expect(r.status, `GET ${p}`).toBe(200);
      }
      const w1 = await http('POST', '/api/delivery-statuses', w.tok.buyer, { code: `qa_${w.id}`, nameRU: 'x', namePL: 'x', orderNo: 99 });
      expect(w1.status).toBe(403);
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 A9 stage dictionary and seeds', { tags: ['for-11-00', 'api'] }, () => {
  const ORDER = ['new', 'proposed', 'accepted', 'in_proforma', 'client_paid', 'with_buyer', 'ordered', 'supplier_paid', 'in_delivery', 'partially_received', 'delivered', 'cancelled'];

  test('TC-A9-01 delivery-statuses holds the 12 stages in funnel order with RU and PL names', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const pl = await http('GET', '/api/delivery-statuses?size=100&sort=orderNo,asc', w.admin);
      const ru = await http('GET', '/api/delivery-statuses?size=100&sort=orderNo,asc', w.admin, undefined, 'ru');
      expect(pl.status).toBe(200);
      const codes = pl.body.content.map((s: any) => s.code);
      expect(codes).toEqual(ORDER);
      for (let i = 0; i < ORDER.length; i += 1) {
        expect(pl.body.content[i].name, ORDER[i]).toBeTruthy();
        expect(ru.body.content[i].name, ORDER[i]).toBeTruthy();
      }
      expect(pl.body.content.map((s: any) => s.orderNo)).toEqual(ORDER.map((_, i) => i + 1));
      // RU and PL labels of the new stages differ (the seed carries both languages)
      expect(ru.body.content[5].name).toBe('У закупщика');
      expect(pl.body.content[5].name).toBe('U zakupowca');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A9-02 role matrix grants of PURCHASE_POSITIONS / PURCHASE_REQUESTS / PROJECT_MATERIALS follow R14.3', async () => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const expected: Record<string, Record<string, string[]>> = {
        ADMIN: { PURCHASE_POSITIONS: ['CREATE', 'READ', 'UPDATE', 'DELETE'], PURCHASE_REQUESTS: ['CREATE', 'READ', 'UPDATE', 'DELETE'], PROJECT_MATERIALS: ['CREATE', 'READ', 'UPDATE', 'DELETE'] },
        MANAGER: { PURCHASE_POSITIONS: ['CREATE', 'READ', 'UPDATE', 'DELETE'], PURCHASE_REQUESTS: ['CREATE', 'READ', 'UPDATE', 'DELETE'], PROJECT_MATERIALS: ['CREATE', 'READ', 'UPDATE', 'DELETE'] },
        BUYER: { PURCHASE_POSITIONS: ['CREATE', 'READ', 'UPDATE'], PURCHASE_REQUESTS: ['READ', 'UPDATE'], PROJECT_MATERIALS: ['CREATE', 'READ', 'UPDATE', 'DELETE'] },
        FOREMAN: { PURCHASE_POSITIONS: ['READ'], PURCHASE_REQUESTS: ['READ'], PROJECT_MATERIALS: ['READ'] },
        CLIENT: {}, WORKER: {}, FINANCIER: {}, ESTIMATOR: {},
      };
      const roles = (await http('GET', '/api/roles?size=100', w.admin)).body.content as Array<{ id: number; code: string }>;
      for (const [role, want] of Object.entries(expected)) {
        const id = roles.find((r) => r.code === role)!.id;
        const perms = (await http('GET', `/api/roles/${id}/permissions`, w.admin)).body.permissions as any[];
        for (const res of ['PURCHASE_POSITIONS', 'PURCHASE_REQUESTS', 'PROJECT_MATERIALS']) {
          const got = (perms.find((p) => p.resourceCode === res)?.operations ?? []).map((o: any) => o.operationCode).sort();
          expect(got, `${role}/${res}`).toEqual([...(want[res] ?? [])].sort());
        }
      }
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-A9-03 seeds are idempotent: no duplicate role / resource / stage rows (partial: liquibase is not re-run)', async () => {
    // Not re-running the liquibase container (the stack must not be restarted); checks the observable result
    // of idempotent seeds instead: one row per code and no duplicated changeset ids.
    expect(psql(`SELECT COUNT(*) FROM (SELECT code FROM roles GROUP BY code HAVING COUNT(*) > 1) d;`)).toBe('0');
    expect(psql(`SELECT COUNT(*) FROM (SELECT code FROM resources GROUP BY code HAVING COUNT(*) > 1) d;`)).toBe('0');
    expect(psql(`SELECT COUNT(*) FROM (SELECT code FROM delivery_statuses GROUP BY code HAVING COUNT(*) > 1) d;`)).toBe('0');
    expect(psql(`SELECT COUNT(*) FROM resources WHERE code IN ('PURCHASE_POSITIONS','PURCHASE_REQUESTS','PROJECT_MATERIALS');`)).toBe('3');
    expect(psql(`SELECT COUNT(*) FROM roles WHERE code = 'BUYER';`)).toBe('1');
    expect(psql(`SELECT COUNT(*) FROM delivery_statuses;`)).toBe('12');
  });
});
