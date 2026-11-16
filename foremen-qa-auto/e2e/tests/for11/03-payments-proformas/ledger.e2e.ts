import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  acceptedPosition, draftProforma, errMatches, feedTxn, getEo, getPf, http, issuedProforma, link, ok, payProforma,
  positionStatus, psql, txn, withCtx, type Ctx,
} from './support.js';
import { createUser } from '../../support/users.js';

// FOR-11-03 part A8-A12 (matching, planner groups, permissions, migrations, payments list, ledger links by HTTP).
// Repeatability: run-id fixture per test (support.ts); BANK_FEED rows live on a STUB connection `qa03-<rid>-conn`,
// all removed by teardown.
describe('FOR-11-03 ledger matching, planner, permissions (API)', { tags: ['for-11-03', 'api', 'ledger'] }, () => {
  const detail = async (c: Ctx, id: number) => (await ok('GET', `/api/bank/transactions/${id}`, c.tok.fin)) as any;
  const detect = (c: Ctx, ids: number[]) => http('POST', '/api/bank/transactions/detect', c.tok.fin, { ids });
  const notes = async (tok: string, type: string): Promise<number> =>
    ((await ok('GET', '/api/notifications?size=100', tok)) as any[]).filter((n) => n.type === type).length;

  test('TC-A8-01 a bank line with the proforma number and amount is matched to the proforma', async () => {
    await withCtx(async (c) => {
      const { id, body } = await issuedProforma(c);
      const t = feedTxn(c, 1890, `${body.number} zaplata`);

      const d = await detect(c, [t]);

      expect(d.status, d.text).toBe(200);
      const det = await detail(c, t);
      // number + amount scores above the auto threshold, so the system links instead of only suggesting
      expect(['AUTO_LINKED', 'SUGGESTED']).toContain(det.line.linkStatus);
      const target = det.line.links[0] ?? det.suggestions[0];
      expect(target.kind).toBe('PROFORMA_PAYMENT');
      expect(target.clientProformaId).toBe(id);
    });
  });

  test('TC-A8-01 (b) a number-only match is a suggestion with reason PROFORMA_NUMBER and notifies the financier once', async () => {
    await withCtx(async (c) => {
      const { id, body } = await issuedProforma(c);
      const t = feedTxn(c, 777, `${body.number} zaplata`);

      await detect(c, [t]);

      const det = await detail(c, t);
      expect(det.line.linkStatus).toBe('SUGGESTED');
      expect(det.suggestions).toHaveLength(1);
      expect(det.suggestions[0]).toMatchObject({ kind: 'PROFORMA_PAYMENT', clientProformaId: id, rationale: 'PROFORMA_NUMBER' });
      await expect.poll(() => notes(c.tok.fin, 'notification.invoice.matchProposed')).toBe(1);
    });
  });

  test('TC-A8-02 an order sent after the bank line appears is matched by the reverse matcher', async () => {
    await withCtx(async (c) => {
      const { p1, p2, proformaId } = await (async () => {
        const r = await issuedProforma(c);
        await payProforma(c, r.id, 1890);
        return { p1: r.pids[0], p2: r.pids[1], proformaId: r.id };
      })();
      void proformaId;
      const eo = await http('POST', '/api/external-orders', c.tok.buyer, {
        projectId: c.projectId, sellerId: c.sellerId,
        header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 50, deliveryVatRateId: c.vat23 },
        lines: [{ positionId: p1, quantity: 6, unitPurchaseNet: 80, aboveCeilingAcknowledged: false }, { positionId: p2, quantity: 3, unitPurchaseNet: 200, aboveCeilingAcknowledged: false }],
      });
      const payable = eo.body.payableGross as number;
      const t = feedTxn(c, -payable, 'platnosc', c.sellerName);

      const sent = await http('POST', `/api/external-orders/${eo.body.id}/send`, c.tok.buyer);

      expect(sent.status, sent.text).toBe(200);
      await expect.poll(async () => {
        const det = await detail(c, t);
        const hit = det.line.links[0] ?? det.suggestions[0];
        return hit ? `${hit.kind}:${hit.externalOrderId}` : 'none';
      }, { timeout: 20_000 }).toBe(`EXTERNAL_ORDER_PAYMENT:${eo.body.id}`);
    });
  });

  test('TC-A8-03 proforma numbers without slashes are recognised', async () => {
    await withCtx(async (c) => {
      const { id, body } = await issuedProforma(c);
      const num = body.number as string;
      const spaced = feedTxn(c, 777, `${num.replace(/\//g, ' ')} x`);
      const dashed = feedTxn(c, 777, `${num.replace(/\//g, '-')} y`);

      await detect(c, [spaced, dashed]);

      for (const t of [spaced, dashed]) {
        const det = await detail(c, t);
        expect(det.suggestions[0], String(t)).toMatchObject({ kind: 'PROFORMA_PAYMENT', clientProformaId: id, rationale: 'PROFORMA_NUMBER' });
      }
    });
  });

  test('TC-A8-04 a suggestion can be confirmed (link created) or rejected (suggestion gone)', async () => {
    await withCtx(async (c) => {
      const { id, body } = await issuedProforma(c);
      const a = feedTxn(c, 777, `${body.number} a`);
      const b = feedTxn(c, 100, `${body.number} b`);
      await detect(c, [a, b]);
      const sa = (await detail(c, a)).suggestions[0];
      const sb = (await detail(c, b)).suggestions[0];

      const ok1 = await http('POST', `/api/bank/suggestions/${sa.id}/confirm`, c.tok.fin, {});
      const rej = await http('POST', `/api/bank/suggestions/${sb.id}/reject`, c.tok.fin, {});

      expect(ok1.status, ok1.text).toBe(200);
      expect(rej.status, rej.text).toBe(200);
      expect((await getPf(c, id)).paidGross).toBe(777);
      expect((await detail(c, b)).suggestions).toHaveLength(0);
    });
  });

  test('TC-A8-05 link targets are searchable by proforma number, order reference and amount', async () => {
    await withCtx(async (c) => {
      const { id, body } = await issuedProforma(c); // stays unpaid: only ISSUED / PARTIALLY_PAID are searchable
      const paid = await issuedProforma(c, [[3, 250]]);
      await payProforma(c, paid.id, 810);
      const eo = await http('POST', '/api/external-orders', c.tok.buyer, {
        projectId: c.projectId, sellerId: c.sellerId, header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 0, deliveryVatRateId: c.vat23 },
        lines: [{ positionId: paid.pids[0], quantity: 3, unitPurchaseNet: 200, aboveCeilingAcknowledged: false }],
      });
      await http('POST', `/api/external-orders/${eo.body.id}/send`, c.tok.buyer);
      const credit = feedTxn(c, 1890, 'x');
      const debit = feedTxn(c, -738, 'y');
      const find = async (t: number, kind: string, q: string) =>
        ((await ok('GET', `/api/bank/transactions/${t}/link-targets?kind=${kind}&q=${encodeURIComponent(q)}`, c.tok.fin)) as any[]).map((x) => x.id);
      const pf2 = await issuedProforma(c, [[1, 40]]);

      expect(await find(credit, 'PROFORMA_PAYMENT', body.number)).toContain(id);
      expect(await find(credit, 'PROFORMA_PAYMENT', '43,20')).toContain(pf2.id);
      expect(await find(debit, 'EXTERNAL_ORDER_PAYMENT', `QA-${c.rid}-ORD`)).toContain(eo.body.id);
    });
  });

  test('TC-A9-01 the planner groups by proforma and by external order', async () => {
    await withCtx(async (c) => {
      const { id, pids } = await issuedProforma(c);
      await payProforma(c, id, 1890);
      await acceptedPosition(c, 1, 40);
      const eo = await http('POST', '/api/external-orders', c.tok.buyer, {
        projectId: c.projectId, sellerId: c.sellerId, header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 50, deliveryVatRateId: c.vat23 },
        lines: [{ positionId: pids[1], quantity: 3, unitPurchaseNet: 200, aboveCeilingAcknowledged: false }],
      });
      const g = (q: string, tok = c.tok.buyer) => http('GET', `/api/purchase-positions/planner/groups?projectId=${c.projectId}&${q}`, tok);

      const pf = await g('groupBy=PROFORMA');
      const ex = await g('groupBy=EXTERNAL_ORDER');
      const bad = await g('groupBy=BAD');
      const req = await g('groupBy=REQUEST');
      const none = await g('groupBy=NONE');

      const keys = (r: any) => (r.body.content as any[]).map((x) => x.key);
      expect(keys(pf)).toEqual([String(id), 'NO_PROFORMA']);
      expect(keys(ex)).toEqual([String(eo.body.id), 'NO_EXTERNAL_ORDER']);
      const exGroup = ex.body.content[0];
      expect(exGroup).toMatchObject({ seller: c.sellerName, deliveryCostNet: 50, status: 'DRAFT', externalOrderId: eo.body.id });
      expect(errMatches(bad, 400, 'error.purchasePlanner.groupBy.invalid')).toBeNull();
      expect(keys(req)).toEqual(['NO_REQUEST']);
      expect(keys(none)).toEqual(['ALL']);
    });
  });

  test('TC-A9-02 planner filters proformaId / externalOrderId and the new row fields', async () => {
    await withCtx(async (c) => {
      const { id, pids } = await issuedProforma(c);
      await payProforma(c, id, 1890);
      const eo = await http('POST', '/api/external-orders', c.tok.buyer, {
        projectId: c.projectId, sellerId: c.sellerId, header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 0, deliveryVatRateId: c.vat23 },
        lines: [{ positionId: pids[1], quantity: 3, unitPurchaseNet: 200, aboveCeilingAcknowledged: false }],
      });
      const rows = async (q: string) => ((await ok('GET', `/api/purchase-positions/planner?projectId=${c.projectId}&${q}`, c.tok.buyer)).content as any[]);

      const byPf = await rows(`proformaId=${id}`);
      const byEo = await rows(`externalOrderId=${eo.body.id}`);

      expect(byPf.map((r) => r.positionId).sort()).toEqual([...pids].sort());
      expect(byPf[0].proformaNumber).toMatch(/^PF\//);
      expect(byEo.map((r) => r.positionId)).toEqual([pids[1]]);
      expect(byEo[0].externalOrderCount).toBe(1);
    });
  });

  test('TC-A9-03 noProforma / noExternalOrder filter on the server (rows, groups, slice counts)', async () => {
    await withCtx(async (c) => {
      const { id, pids } = await issuedProforma(c);
      await payProforma(c, id, 1890);
      const p3 = await acceptedPosition(c, 1, 40);
      const [p1, p2] = pids;
      const eo = await http('POST', '/api/external-orders', c.tok.buyer, {
        projectId: c.projectId, sellerId: c.sellerId, header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 0, deliveryVatRateId: c.vat23 },
        lines: [{ positionId: p2, quantity: 3, unitPurchaseNet: 200, aboveCeilingAcknowledged: false }],
      });
      expect(eo.status, eo.text).toBe(200);
      const get = async (q: string) => (await ok('GET', `/api/purchase-positions/planner?projectId=${c.projectId}&${q}`, c.tok.buyer)) as any;
      const ids = (r: any) => (r.content as any[]).map((x) => x.positionId).sort();

      const noPf = await get('noProforma=true');
      const noEo = await get('noExternalOrder=true');
      const both = await get('noProforma=true&noExternalOrder=true');
      const groups = await ok('GET', `/api/purchase-positions/planner/groups?projectId=${c.projectId}&groupBy=PROFORMA&noProforma=true`, c.tok.buyer);
      const slices = await ok('GET', `/api/purchase-positions/planner/slice-counts?projectId=${c.projectId}&noProforma=true`, c.tok.buyer);

      expect(ids(noPf)).toEqual([p3]);
      expect(ids(noEo)).toEqual([p1, p3].sort());
      expect(ids(both)).toEqual([p3]);
      expect(noPf.totalElements).toBe(1);
      expect(groups.content.map((g: any) => g.key)).toEqual(['NO_PROFORMA']);
      expect(groups.content[0].positionCount).toBe(1);
      expect(slices.ALL).toBe(1);
    });
  });

  test('TC-A10-01 the role matrix of CLIENT_PROFORMAS and EXTERNAL_ORDERS', async () => {
    await withCtx(async (c) => {
      const foremanUser = createUser('FOREMAN', 'p03fm');
      c.extraUsers.push(foremanUser);
      const foreman = (await http('POST', '/api/auth/login', undefined, { email: foremanUser.email, password: foremanUser.password })).body.accessToken as string;
      const NO = 999999999;
      const probes: Array<[string, (t: string) => Promise<number>]> = [
        ['PF read', (t) => http('GET', `/api/client-proformas/${NO}`, t).then((r) => r.status)],
        ['PF create', (t) => http('POST', '/api/client-proformas', t, {}).then((r) => r.status)],
        ['PF update', (t) => http('PUT', `/api/client-proformas/${NO}`, t, {}).then((r) => r.status)],
        ['PF delete', (t) => http('DELETE', `/api/client-proformas/${NO}`, t).then((r) => r.status)],
        ['EO read', (t) => http('GET', `/api/external-orders/${NO}`, t).then((r) => r.status)],
        ['EO create', (t) => http('POST', '/api/external-orders', t, {}).then((r) => r.status)],
        ['EO update', (t) => http('PUT', `/api/external-orders/${NO}`, t, {}).then((r) => r.status)],
        ['EO delete', (t) => http('DELETE', `/api/external-orders/${NO}`, t).then((r) => r.status)],
        ['EO approve', (t) => http('POST', `/api/external-orders/${NO}/unmark-paid`, t).then((r) => r.status)],
      ];
      // allowed operations per role (a denied operation answers 403, an allowed one anything else)
      const allowed: Record<string, string[]> = {
        admin: probes.map((p) => p[0]),
        mgr: ['PF read', 'PF create', 'PF update', 'PF delete', 'EO read'],
        buyer: ['PF read', 'EO read', 'EO create', 'EO update', 'EO delete'],
        fin: ['PF read', 'EO read', 'EO approve'],
        client: [],
        foreman: [],
      };
      const tokens: Record<string, string> = { admin: c.tok.admin, mgr: c.tok.mgr, buyer: c.tok.buyer, fin: c.tok.fin, client: c.tok.client, foreman };

      const got: Record<string, string[]> = {};
      for (const [role, t] of Object.entries(tokens)) {
        got[role] = [];
        for (const [name, run] of probes) if ((await run(t)) !== 403) got[role].push(name);
      }

      expect(got).toEqual(allowed);
      const clientList = await http('GET', `/api/client-proformas/client/projects/${c.projectId}`, c.tok.client);
      expect(clientList.status).toBe(200);
    });
  });

  test('TC-A10-02 a manager of another project cannot reach a proforma; buyer and financier can', async () => {
    await withCtx(async (c) => {
      const pos = await acceptedPosition(c, 1, 40, { projectId: c.otherProjectId });
      const draft = await ok('POST', '/api/client-proformas', c.tok.admin, { projectId: c.otherProjectId, positionIds: [pos] });

      const mgr = await http('GET', `/api/client-proformas/${draft.id}`, c.tok.mgr);
      const mgrList = await http('GET', '/api/client-proformas?size=200', c.tok.mgr);
      const buyer = await http('GET', `/api/client-proformas/${draft.id}`, c.tok.buyer);
      const fin = await http('GET', `/api/client-proformas/${draft.id}`, c.tok.fin);

      expect(mgr.status).toBe(404);
      expect(JSON.stringify(mgrList.body)).not.toContain(`"id":${draft.id},`);
      expect(buyer.status).toBe(200);
      expect(fin.status).toBe(200);
    }, { second: true });
  });

  test('TC-A11-01 (partial) the seeded resources and grants have no duplicates', async () => {
    const dup = psql(
      `SELECT count(*) FROM (SELECT code FROM resources GROUP BY code HAVING count(*) > 1) x;
       SELECT count(*) FROM (SELECT role_id, resource_id FROM role_resources GROUP BY 1,2 HAVING count(*) > 1) y;
       SELECT count(*) FROM resources WHERE code IN ('CLIENT_PROFORMAS','EXTERNAL_ORDERS');`,
    ).split('\n');

    expect(dup).toEqual(['0', '0', '2']);
  });

  test('TC-A11-02 CHECK constraints reject malformed ledger links', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);
      const t = txn(c, 100, 'chk');
      const insert = (kind: string, proformaId: string) =>
        psql(
          `INSERT INTO bank_transaction_links (transaction_id, kind, amount, source, client_proforma_id)
           VALUES (:t, :'kind', 100, 'MANUAL', ${proformaId});`,
          { t: String(t), kind },
        );

      let workerErr = '';
      let proformaErr = '';
      try { insert('WORKER_PAYOUT', String(id)); } catch (e) { workerErr = String(e); }
      try { insert('PROFORMA_PAYMENT', 'NULL'); } catch (e) { proformaErr = String(e); }

      expect(workerErr).toContain('violates check constraint');
      expect(proformaErr).toContain('violates check constraint');
      expect(psql('SELECT count(*) FROM bank_transaction_links WHERE transaction_id = :t;', { t: String(t) })).toBe('0');
    });
  });

  test('TC-A12-01 the staff proforma card lists payments, the client card does not', async () => {
    await withCtx(async (c) => {
      const { id } = await issuedProforma(c);
      const none = await getPf(c, id);
      const t1 = txn(c, 700, 'PF one');
      const l1 = await link(c, c.tok.fin, t1, { kind: 'PROFORMA_PAYMENT', amount: 630, clientProformaId: id });
      const one = await getPf(c, id);
      await link(c, c.tok.fin, txn(c, 1260, 'PF two'), { kind: 'PROFORMA_PAYMENT', amount: 1260, clientProformaId: id });
      const two = await getPf(c, id);
      const client = await http('GET', `/api/client-proformas/client/${id}`, c.tok.client);

      expect(none.payments).toEqual([]);
      expect(l1.status, l1.text).toBe(201);
      expect(one.payments).toHaveLength(1);
      expect(one.payments[0]).toMatchObject({ linkId: l1.body.linkId, amount: 630 });
      expect(Object.keys(one.payments[0]).sort()).toEqual(['amount', 'bookingDate', 'linkId', 'title']);
      expect(two.status).toBe('PAID');
      expect(two.payments).toHaveLength(2);
      expect(client.body).not.toHaveProperty('payments');
    });
  });

  test('TC-A12-03 ledger links by HTTP: replace and remove effects on an order', async () => {
    await withCtx(async (c) => {
      const r = await issuedProforma(c);
      await payProforma(c, r.id, 1890);
      const eo = await http('POST', '/api/external-orders', c.tok.buyer, {
        projectId: c.projectId, sellerId: c.sellerId, header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 0, deliveryVatRateId: c.vat23 },
        lines: [{ positionId: r.pids[1], quantity: 3, unitPurchaseNet: 200, aboveCeilingAcknowledged: false }],
      });
      const sent = await http('POST', `/api/external-orders/${eo.body.id}/send`, c.tok.buyer);
      const n = sent.body.payableGross as number; // 738
      const t = txn(c, -n, 'EO');
      const body = (amount: number) => ({ kind: 'EXTERNAL_ORDER_PAYMENT', amount, externalOrderId: eo.body.id });

      const l1 = await link(c, c.tok.fin, t, body(n));
      expect(l1.status, l1.text).toBe(201);
      expect((await getEo(c, eo.body.id)).status).toBe('PAID');
      expect((await getEo(c, eo.body.id)).paidSource).toBe('LEDGER');
      expect(await positionStatus(c, r.pids[1])).toBe('SUPPLIER_PAID');

      const put = await http('PUT', `/api/bank/links/${l1.body.linkId}`, c.tok.fin, body(n - 10));
      expect(put.status, put.text).toBe(200);
      const after = await getEo(c, eo.body.id);
      expect(after.status).toBe('SENT_FOR_PAYMENT');
      expect(after.paidSource).toBeNull();
      expect(await positionStatus(c, r.pids[1])).toBe('ORDERED');

      const top = await link(c, c.tok.admin, t, body(10));
      expect(top.status, top.text).toBe(201);
      expect((await getEo(c, eo.body.id)).status).toBe('PAID');
      for (const id of [put.body.linkId, top.body.linkId]) expect((await http('DELETE', `/api/bank/links/${id}`, c.tok.fin)).status).toBe(200);
      expect((await getEo(c, eo.body.id)).status).toBe('SENT_FOR_PAYMENT');
      expect(await positionStatus(c, r.pids[1])).toBe('ORDERED');
    });
  });

  test('TC-A12-03 (4) ledger links by HTTP: replace on a proforma, released links are locked', async () => {
    await withCtx(async (c) => {
      const { id, pids } = await issuedProforma(c);
      const t = txn(c, 1890, 'PF');
      const l = await link(c, c.tok.fin, t, { kind: 'PROFORMA_PAYMENT', amount: 500, clientProformaId: id });
      expect((await getPf(c, id)).status).toBe('PARTIALLY_PAID');

      const put = await http('PUT', `/api/bank/links/${l.body.linkId}`, c.tok.fin, { kind: 'PROFORMA_PAYMENT', amount: 800, clientProformaId: id });
      expect(put.status, put.text).toBe(200);
      expect((await getPf(c, id)).paidGross).toBe(800);
      const rest = await link(c, c.tok.fin, t, { kind: 'PROFORMA_PAYMENT', amount: 1090, clientProformaId: id });
      expect(rest.status, rest.text).toBe(201);
      expect((await getPf(c, id)).status).toBe('PAID');
      expect(await positionStatus(c, pids[0])).toBe('WITH_BUYER');

      const del = await http('DELETE', `/api/bank/links/${put.body.linkId}`, c.tok.fin);
      const put2 = await http('PUT', `/api/bank/links/${put.body.linkId}`, c.tok.fin, { kind: 'PROFORMA_PAYMENT', amount: 700, clientProformaId: id });
      expect(del.status).toBe(409);
      expect(put2.status).toBe(409);
      expect((await getPf(c, id)).paidGross).toBe(1890);
    });
  });

  test('TC-A12-03 (5) invalid ledger link requests record nothing', async () => {
    await withCtx(async (c) => {
      const draft = await draftProforma(c, [await acceptedPosition(c, 1, 40)]);
      const issued = await issuedProforma(c, [[1, 40]]);
      const t = txn(c, 100, 'PF');
      const tEur = txn(c, 100, 'PF eur', 'EUR');
      const tDebit = txn(c, -100, 'debit');
      const pf = (clientProformaId: number, amount = 10) => ({ kind: 'PROFORMA_PAYMENT', amount, clientProformaId });

      const onDraft = await link(c, c.tok.fin, t, pf(draft.id));
      const debit = await link(c, c.tok.fin, tDebit, pf(issued.id));
      const eur = await link(c, c.tok.fin, tEur, pf(issued.id));
      const over = await link(c, c.tok.fin, t, pf(issued.id, 100)); // proforma gross is 43.20

      expect(onDraft.status).toBe(409);
      expect(debit.status).toBe(400);
      expect(eur.status).toBe(409);
      expect(over.status).toBe(409);
      expect((await getPf(c, issued.id)).paidGross).toBe(0);
      expect(psql(`SELECT count(*) FROM bank_transaction_links WHERE transaction_id IN (:a, :b, :c);`, { a: String(t), b: String(tEur), c: String(tDebit) })).toBe('0');
      sweep(c);
    });
  });
  const sweep = (c: Ctx) => void c;
});
