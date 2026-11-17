import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  acceptedPosition, draftProforma, pp, pfOf, errMatches, getBytes, getPf, http, issueProforma, issuedProforma, link, login, ok,
  payProforma, positionStatus, psql, txn, withCtx, companyProfile, sweepForeignAccounts, type Ctx,
} from './support.js';

// FOR-11-03 part A1-A4 (proforma draft, issue, client surface, payment gateway).
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-03-payments-proformas-external-orders/test-cases.md.
// Repeatability: every test builds its own run-id fixture (project QA03-<rid>, users, seller, CASH account
// `qa03-<rid>-acc`, ledger rows titled `qa-<rid> ...`) and removes all of it in `finally` (support.ts teardown).
// ENV NOTE: issuing a proforma writes the PDF to GCS; the stack's ADC token is expired, so
// POST /issue answers 502 error.proforma.pdf.failed. issueProforma() then emulates the issue in SQL.
describe('FOR-11-03 proformas (API)', { tags: ['for-11-03', 'api', 'proforma'] }, () => {
  const needsRealIssue = async (c: Ctx): Promise<{ pids: number[]; id: number; body: any }> => {
    const p1 = await acceptedPosition(c, 10, 100);
    const p2 = await acceptedPosition(c, 3, 250);
    const d = await draftProforma(c, [p1, p2]);
    const r = await http('POST', `/api/client-payments/${pp(d.id)}/issue`, c.tok.mgr, { language: 'pl' });
    if (r.status === 502) test.skip('ENV: PDF storage (GCS ADC token expired) -> POST /issue answers 502, real issue not testable');
    expect(r.status, r.text).toBe(200);
    return { pids: [p1, p2], id: d.id, body: r.body };
  };

  test('TC-A1-01 a draft is created with correct totals and leaves positions ACCEPTED', async () => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const p2 = await acceptedPosition(c, 3, 250);

      const r = await http('POST', '/api/client-payments/materials', c.tok.mgr, { projectId: c.projectId, positionIds: [p1, p2], dueDate: null, note: `qa-${c.rid}-1` });

      expect(r.status).toBe(200);
      expect(r.body.status).toBe('DRAFT');
      expect(r.body.number).toBeNull();
      expect(r.body.lines).toHaveLength(2);
      expect(r.body.totalNet).toBe(1750);
      expect(r.body.totalVat).toBe(140);
      expect(r.body.totalGross).toBe(1890);
      const g = await getPf(c, pfOf(r.body.id));
      expect(g.totalGross).toBe(1890);
      expect(await positionStatus(c, p1)).toBe('ACCEPTED');
      expect(await positionStatus(c, p2)).toBe('ACCEPTED');
    });
  });

  test('TC-A1-01 step 3 selection total equals the proforma gross', async () => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const p2 = await acceptedPosition(c, 3, 250);
      const d = await draftProforma(c, [p1, p2]);

      const sel = await http('POST', '/api/purchase-positions/selection-total', c.tok.mgr, { positionIds: [p1, p2] });

      expect(sel.status, sel.text).toBe(200);
      expect(JSON.stringify(sel.body)).toContain(String(d.totalGross));
    });
  });

  test('TC-A1-02 composition validations reject foreign, planned, package and unpriced positions', async () => {
    await withCtx(async (c) => {
      const other = await acceptedPosition(c, 1, 10, { projectId: c.otherProjectId });
      const planned = (await ok('POST', '/api/purchase-positions', c.tok.admin, {
        projectId: c.projectId, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: c.materialId, quantity: 1, salePrice: 10, saleVatRateId: c.vat8,
      })).id as number;
      const pkg = (await ok('POST', '/api/purchase-positions', c.tok.admin, {
        projectId: c.projectId, kind: 'FINISHING', sourcingPath: 'PACKAGE', plannedMaterialId: c.materialId, quantity: 1, salePrice: 10, saleVatRateId: c.vat8,
      })).id as number;
      const noPrice = await acceptedPosition(c, 1, 10);
      psql('UPDATE purchase_positions SET sale_price = NULL WHERE id = :id;', { id: String(noPrice) });
      const p1 = await acceptedPosition(c, 10, 100);
      await draftProforma(c, [p1]);
      const attempt = (ids: number[]) => http('POST', '/api/client-payments/materials', c.tok.mgr, { projectId: c.projectId, positionIds: ids });

      expect(errMatches(await attempt([other]), 409, 'error.proforma.position.projectMismatch')).toBeNull();
      expect(errMatches(await attempt([planned]), 409, 'error.proforma.position.notAccepted')).toBeNull();
      expect(errMatches(await attempt([pkg]), 409, 'error.proforma.position.notAgreed')).toBeNull();
      expect(errMatches(await attempt([noPrice]), 409, 'error.proforma.position.noSalePrice')).toBeNull();
      expect(errMatches(await attempt([p1]), 409, 'error.proforma.position.alreadyInProforma')).toBeNull();
    }, { second: true });
  });

  test('TC-A1-03 a draft can be edited and deleted, positions stay ACCEPTED', async () => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const p2 = await acceptedPosition(c, 3, 250);
      const p3 = await acceptedPosition(c, 1, 40);
      const d = await draftProforma(c, [p1, p2]);

      const add = await http('PUT', `/api/client-payments/${pp(d.id)}`, c.tok.mgr, { positionIds: [p1, p2, p3], dueDate: '2099-01-01', note: `qa-${c.rid}-2` });
      expect(add.status, add.text).toBe(200);
      expect(add.body.totalGross).toBeCloseTo(1890 + 43.2, 2);
      expect(add.body.dueDate).toBe('2099-01-01');
      const rm = await http('PUT', `/api/client-payments/${pp(d.id)}`, c.tok.mgr, { positionIds: [p1, p2] });
      expect(rm.body.totalGross).toBe(1890);
      expect(await positionStatus(c, p3)).toBe('ACCEPTED');
      const payId = pp(d.id);
      const del = await http('DELETE', `/api/client-payments/${payId}`, c.tok.mgr);
      expect(del.status).toBe(204);
      expect((await http('GET', `/api/client-payments/${payId}`, c.tok.mgr)).status).toBe(404);
      const again = await draftProforma(c, [p1, p2]);
      expect(again.status).toBe('DRAFT');
      expect(await positionStatus(c, p1)).toBe('ACCEPTED');
    });
  });

  test('TC-A2-01 issue assigns a number, freezes positions and serves a PDF', async () => {
    await withCtx(async (c) => {
      const { pids, id, body } = await needsRealIssue(c);

      expect(body.status).toBe('ISSUED');
      expect(body.number).toMatch(/^PF\/\d{4}\/\d{6}$/);
      const today = new Date().toISOString().slice(0, 10);
      expect(body.issueDate).toBe(today);
      for (const p of pids) expect(await positionStatus(c, p)).toBe('IN_PROFORMA');
      const pdf = await getBytes(`/api/client-payments/${pp(id)}/pdf`, c.tok.mgr);
      expect(pdf.status).toBe(200);
      expect(pdf.type).toContain('application/pdf');
      expect(pdf.bytes.subarray(0, 4).toString()).toBe('%PDF');
      const again = await http('POST', `/api/client-payments/${pp(id)}/issue`, c.tok.mgr, {});
      expect(errMatches(again, 409, 'error.proforma.notDraft') && errMatches(again, 409, 'error.proforma.status.notAllowed')).toBeNull();
    });
  });

  test('TC-A2-01 (partial) a failed issue leaves the draft and the positions untouched', async () => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const d = await draftProforma(c, [p1]);

      const r = await http('POST', `/api/client-payments/${pp(d.id)}/issue`, c.tok.mgr, { language: 'pl' });

      if (r.status === 200) test.skip('real issue works here; covered by TC-A2-01');
      expect(errMatches(r, 502, 'error.proforma.pdf.failed')).toBeNull();
      expect((await getPf(c, d.id)).status).toBe('DRAFT');
      expect(await positionStatus(c, p1)).toBe('ACCEPTED');
    });
  });

  test('TC-A2-02 positions of an issued proforma are frozen', async () => {
    await withCtx(async (c) => {
      const { pids } = await issuedProforma(c);

      const otherMaterial = (await ok('GET', '/api/finishing-materials?size=3', c.tok.admin)).content.find((m: any) => m.id !== c.materialId).id;
      for (const patch of [{ quantity: 11 }, { salePrice: 120 }, { saleVatRateId: c.vat23 }, { plannedMaterialId: otherMaterial }]) {
        const r = await http('PUT', `/api/purchase-positions/${pids[0]}`, c.tok.mgr, patch);
        expect(errMatches(r, 409, 'error.proforma.position.locked'), JSON.stringify(patch)).toBeNull();
      }
    });
  });

  test('TC-A2-03 proforma numbers are distinct and increasing', async () => {
    await withCtx(async (c) => {
      const a = await needsRealIssue(c);
      const p3 = await acceptedPosition(c, 1, 40);
      const d2 = await draftProforma(c, [p3]);

      const b = await ok('POST', `/api/client-payments/${pp(d2.id)}/issue`, c.tok.mgr, { language: 'pl' });

      expect(b.number).not.toBe(a.body.number);
      expect(b.number > a.body.number).toBe(true);
    });
  });

  test('TC-A2-04 an unpaid issued proforma can be cancelled and positions return to ACCEPTED', async () => {
    await withCtx(async (c) => {
      const { pids, id } = await issuedProforma(c, [[1, 40]]);

      const r = await http('POST', `/api/client-payments/${pp(id)}/cancel`, c.tok.mgr, { reason: `qa-${c.rid}` });

      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('CANCELLED');
      expect(r.body.cancelReason).toBe(`qa-${c.rid}`);
      expect(await positionStatus(c, pids[0])).toBe('ACCEPTED');
      const again = await http('POST', '/api/client-payments/materials', c.tok.mgr, { projectId: c.projectId, positionIds: pids });
      expect(again.status, again.text).toBe(200);
    });
  });

  test('TC-A2-05 cancelling an issued proforma without a reason is rejected', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c, [[1, 40]]);

      const r = await http('POST', `/api/client-payments/${pp(id)}/cancel`, c.tok.mgr, {});

      expect(r.status, r.text).toBe(400);
      expect((await getPf(c, id)).status).toBe('ISSUED');
    });
  });

  test('TC-A2-06 a company profile without a bank account blocks the issue', async () => {
    await withCtx(async (c) => {
      if (!c.profileCreated) test.skip('company profile is not owned by this run; not touching shared data');
      const p1 = await acceptedPosition(c, 10, 100);
      const d = await draftProforma(c, [p1]);
      await ok('PUT', '/api/company-profile', c.tok.admin, companyProfile(c.rid, null));

      const r = await http('POST', `/api/client-payments/${pp(d.id)}/issue`, c.tok.mgr, { language: 'pl' });

      expect(errMatches(r, 409, 'error.proforma.companyProfile.incomplete')).toBeNull();
      expect(await positionStatus(c, p1)).toBe('ACCEPTED');
      expect((await getPf(c, d.id)).status).toBe('DRAFT');
    });
  });

  test('TC-A3-01 a client sees the own issued proforma without internal data', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);

      const list = await http('GET', `/api/client-payments?kind=MATERIALS&projectId=${c.projectId}`, c.tok.client);
      const one = await http('GET', `/api/client-payments/${pp(id)}`, c.tok.client);

      expect(list.status, list.text).toBe(200);
      expect(one.status, one.text).toBe(200);
      for (const k of ['number', 'status', 'amountGross', 'lines']) expect(one.body, k).toHaveProperty(k);
      expect(one.body.lines.length).toBeGreaterThan(0);
      const json = JSON.stringify(one.body).toLowerCase();
      for (const bad of ['purchase', 'seller', 'margin', 'delivery', 'externalorder', 'payments']) expect(json, bad).not.toContain(bad);
      expect(JSON.stringify(list.body)).toContain(`"id":${pp(id)},`);
    });
  });

  test('TC-A3-01 (PDF) a client downloads the proforma PDF', async () => {
    await withCtx(async (c) => {
      const { id } = await needsRealIssue(c);

      const pdf = await getBytes(`/api/client-payments/${pp(id)}/pdf`, c.tok.client);

      expect(pdf.status).toBe(200);
      expect(pdf.bytes.subarray(0, 4).toString()).toBe('%PDF');
    });
  });

  test('TC-A3-02 a draft is invisible to the client', async () => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100);
      const d = await draftProforma(c, [p1]);

      const list = await http('GET', `/api/client-payments?kind=MATERIALS&projectId=${c.projectId}`, c.tok.client);
      const one = await http('GET', `/api/client-payments/${pp(d.id)}`, c.tok.client);

      expect(list.status, list.text).toBe(200);
      expect(JSON.stringify(list.body)).not.toContain(`"id":${pp(d.id)},`);
      expect(one.status).toBe(404);
    });
  });

  test('TC-A3-03 client isolation: other project, staff endpoints and procurement endpoints', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);
      const other = await login(c.extraUsers[0]);

      const foreign = await http('GET', `/api/client-payments/${pp(id)}`, other);
      const staff = await http('POST', '/api/client-payments/materials', c.tok.client, {});
      const orders = await http('GET', '/api/external-orders', c.tok.client);
      const positions = await http('GET', '/api/purchase-positions', c.tok.client);

      expect(foreign.status).toBe(404);
      expect(staff.status).toBe(403);
      expect(orders.status).toBe(403);
      expect(positions.status).toBe(403);
    }, { second: true });
  });

  test('TC-A3-04 purchase-price sentinels never reach the client surface', async () => {
    await withCtx(async (c) => {
      const p1 = await acceptedPosition(c, 10, 100, { purchasePrice: 777.77 });
      const d = await draftProforma(c, [p1]);
      const { id } = { id: d.id as number };
      await issueProforma(c, id);
      await payProforma(c, id, d.totalGross);
      const eo = await http('POST', '/api/external-orders', c.tok.buyer, {
        projectId: c.projectId, sellerId: c.sellerId, header: { referenceNumber: `QA-${c.rid}-ORD` },
        lines: [{ positionId: p1, quantity: 10, unitPurchaseNet: 666.66, purchaseVatRateId: c.vat23, aboveCeilingAcknowledged: true }],
      });
      expect(eo.status, eo.text).toBe(200);

      const list = await http('GET', `/api/client-payments?kind=MATERIALS&projectId=${c.projectId}`, c.tok.client);
      const one = await http('GET', `/api/client-payments/${pp(id)}`, c.tok.client);

      const all = JSON.stringify([list.body, one.body]);
      for (const s of ['777.77', '666.66', `QA03-${c.rid}-SELLER`]) expect(all, s).not.toContain(s);
    });
  });

  test('TC-A4-01 a partial payment does not release positions', async () => {
    await withCtx(async (c) => {
      const { pids, id } = await issuedProforma(c);
      const t1 = txn(c, 1000, 'PF part');

      const r = await link(c, c.tok.fin, t1, { kind: 'PROFORMA_PAYMENT', amount: 1000, clientProformaId: id });

      expect(r.status, r.text).toBe(201);
      const pf = await getPf(c, id);
      expect(pf.status).toBe('PARTIALLY_PAID');
      expect(pf.paidGross).toBe(1000);
      for (const p of pids) expect(await positionStatus(c, p)).toBe('IN_PROFORMA');
      const notes = (await ok('GET', '/api/notifications?size=50', c.tok.mgr)) as any[];
      expect(notes.filter((n) => n.type === 'notification.proforma.paid')).toHaveLength(0);
      const bn = (await ok('GET', '/api/notifications?size=50', c.tok.buyer)) as any[];
      expect(bn.filter((n) => n.type === 'notification.order.releasedToBuyer')).toHaveLength(0);
    });
  });

  test('TC-A4-02 full payment releases positions to the buyer and notifies', async () => {
    await withCtx(async (c) => {
      const { pids, id } = await issuedProforma(c);
      await link(c, c.tok.fin, txn(c, 1000, 'PF part'), { kind: 'PROFORMA_PAYMENT', amount: 1000, clientProformaId: id });

      const r = await link(c, c.tok.fin, txn(c, 890, 'PF rest'), { kind: 'PROFORMA_PAYMENT', amount: 890, clientProformaId: id });

      expect(r.status, r.text).toBe(201);
      const pf = await getPf(c, id);
      expect(pf.status).toBe('PAID');
      // FOR-11-06: the unified view has no paidAt (R1.6); fully paid = nothing remaining.
      expect(pf.remainingGross).toBe(0);
      expect(pf.releasedAt).toBeTruthy();
      for (const p of pids) expect(await positionStatus(c, p)).toBe('WITH_BUYER');
      const board = await http('GET', `/api/purchase-positions/planner?projectId=${c.projectId}&status=WITH_BUYER&size=50`, c.tok.buyer);
      expect(board.status, board.text).toBe(200);
      expect(JSON.stringify(board.body)).toContain(`"positionId":${pids[0]},`);
      await expect.poll(async () => ((await ok('GET', '/api/notifications?size=50', c.tok.mgr)) as any[]).filter((n) => n.type === 'notification.proforma.paid').length).toBe(1);
      const bn = (await ok('GET', '/api/notifications?size=50', c.tok.buyer)) as any[];
      expect(bn.filter((n) => n.type === 'notification.order.releasedToBuyer')).toHaveLength(1);
    });
  });

  test('TC-A4-03 a payment above the remaining amount is refused', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);
      await payProforma(c, id, 1890);

      const r = await link(c, c.tok.fin, txn(c, 10, 'PF extra'), { kind: 'PROFORMA_PAYMENT', amount: 10, clientProformaId: id });

      expect(r.status).toBe(409);
      expect((await getPf(c, id)).paidGross).toBe(1890);
    });
  });

  test('TC-A4-04 a link of a released proforma cannot be deleted', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);
      const t = txn(c, 1890, 'PF full');
      const l = await link(c, c.tok.fin, t, { kind: 'PROFORMA_PAYMENT', amount: 1890, clientProformaId: id });

      const del = await http('DELETE', `/api/bank/links/${l.body.linkId}`, c.tok.fin);

      expect(errMatches(del, 409, 'error.bank.link.locked')).toBeNull();
      expect((await getPf(c, id)).status).toBe('PAID');
    });
  });

  test('TC-A4-05 deleting a link before release returns the proforma to ISSUED', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);
      const l = await link(c, c.tok.fin, txn(c, 500, 'PF part'), { kind: 'PROFORMA_PAYMENT', amount: 500, clientProformaId: id });
      expect((await getPf(c, id)).status).toBe('PARTIALLY_PAID');

      const del = await http('DELETE', `/api/bank/links/${l.body.linkId}`, c.tok.fin);

      expect(del.status, del.text).toBeLessThan(300);
      const pf = await getPf(c, id);
      expect(pf.status).toBe('ISSUED');
      expect(pf.paidGross).toBe(0);
    });
  });

  test('TC-A4-06 direction and currency are enforced', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);

      const debit = await link(c, c.tok.fin, txn(c, -100, 'PF wrong direction'), { kind: 'PROFORMA_PAYMENT', amount: 100, clientProformaId: id });
      const eur = await link(c, c.tok.fin, txn(c, 100, 'PF eur', 'EUR'), { kind: 'PROFORMA_PAYMENT', amount: 100, clientProformaId: id });

      expect([400, 409]).toContain(debit.status); // test-cases says 409, A12-03 says 400
      expect(errMatches(debit, debit.status, 'error.bank.link.kindDirection')).toBeNull();
      expect(errMatches(eur, 409, 'error.bank.link.currencyMismatch')).toBeNull();
      sweepForeignAccounts(c);
      expect((await getPf(c, id)).paidGross).toBe(0);
    });
  });

  test('TC-A4-07 (partial) repeated detection does not change statuses or notifications', async () => {
    await withCtx(async (c) => {
      const { pids, id } = await issuedProforma(c);
      const t = await payProforma(c, id, 1890);
      const count = async () => ((await ok('GET', '/api/notifications?size=100', c.tok.buyer)) as any[]).filter((n) => n.type === 'notification.order.releasedToBuyer').length;
      const before = await count();

      const d = await http('POST', '/api/bank/transactions/detect', c.tok.fin, { ids: [t] });

      expect(d.status, d.text).toBe(200);
      expect(await count()).toBe(before);
      for (const p of pids) expect(await positionStatus(c, p)).toBe('WITH_BUYER');
    });
  });

  test('TC-A4-08 a proforma with payments cannot be cancelled', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);
      await link(c, c.tok.fin, txn(c, 500, 'PF part'), { kind: 'PROFORMA_PAYMENT', amount: 500, clientProformaId: id });

      const r = await http('POST', `/api/client-payments/${pp(id)}/cancel`, c.tok.mgr, { reason: `qa-${c.rid}` });

      expect(errMatches(r, 409, 'error.proforma.cancel.hasPayments')).toBeNull();
    });
  });
});
