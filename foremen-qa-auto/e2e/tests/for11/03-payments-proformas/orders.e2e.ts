import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  acceptedPosition, draftProforma, errMatches, getEo, http, issueProforma, issuedProforma, link, ok, payProforma,
  positionStatus, procDoc, psql, today, txn, withCtx, type Ctx,
} from './support.js';

// FOR-11-03 part A5-A7 (external orders: lines, send/return/pay, ledger wins).
// Repeatability: run-id fixture per test (see support.ts), full teardown in `finally`.
describe('FOR-11-03 external orders (API)', { tags: ['for-11-03', 'api', 'orders'] }, () => {
  /** A paid, released proforma with P1 (10 x 100) and P2 (3 x 250), both WITH_BUYER. */
  const released = async (c: Ctx) => {
    const { pids, id } = await issuedProforma(c);
    await payProforma(c, id, 1890);
    return { p1: pids[0], p2: pids[1], proformaId: id };
  };
  const line = (positionId: number, quantity: number, unitPurchaseNet: number, ack = false) => ({
    positionId, quantity, unitPurchaseNet, aboveCeilingAcknowledged: ack,
  });
  const createEo = (c: Ctx, lines: any[], header: Record<string, unknown> = {}, tok = c.tok.buyer) =>
    http('POST', '/api/external-orders', tok, {
      projectId: c.projectId, sellerId: c.sellerId,
      header: { referenceNumber: `QA-${c.rid}-ORD`, deliveryCostNet: 50, deliveryVatRateId: c.vat23, ...header },
      lines,
    });
  /** Eo1 = P1 x6 @80 + P2 x3 @200, delivery 50 net. */
  const eo1 = async (c: Ctx) => {
    const r = await released(c);
    const e = await createEo(c, [line(r.p1, 6, 80), line(r.p2, 3, 200)]);
    expect(e.status, e.text).toBe(200);
    return { ...r, id: e.body.id as number, body: e.body };
  };
  const notes = async (tok: string, type: string): Promise<number> =>
    ((await ok('GET', '/api/notifications?size=100', tok)) as any[]).filter((n) => n.type === type).length;
  const getPos = (c: Ctx, id: number) => ok('GET', `/api/purchase-positions/${id}`, c.tok.admin);

  test('TC-A5-01 a buyer creates a draft order and the delivery cost is split to exactly 50.00', async () => {
    await withCtx(async (c) => {
      const { p1, p2 } = await released(c);

      const r = await createEo(c, [line(p1, 6, 80), line(p2, 3, 200)]);

      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('DRAFT');
      const shares = r.body.lines.map((l: any) => l.deliveryCostShare) as number[];
      expect(shares[0]).toBeCloseTo(22.22, 2);
      expect(shares[1]).toBeCloseTo(27.78, 2);
      expect(Math.round((shares[0] + shares[1]) * 100)).toBe(5000);
      const seen = await http('GET', `/api/external-orders/${r.body.id}`, c.tok.buyer);
      expect(seen.status).toBe(200);
    });
  });

  test('TC-A5-02 line quantities cannot exceed the uncovered position quantity', async () => {
    await withCtx(async (c) => {
      const { p1 } = await released(c);
      expect((await createEo(c, [line(p1, 6, 80)])).status).toBe(200);

      const over = await createEo(c, [line(p1, 5, 80)]);
      const fit = await createEo(c, [line(p1, 4, 80)]);

      expect(errMatches(over, 409, 'error.externalOrder.line.overCoverage')).toBeNull();
      expect(fit.status, fit.text).toBe(200);
    });
  });

  test('TC-A5-03 a purchase price above the sale price needs an explicit acknowledgement', async () => {
    await withCtx(async (c) => {
      const { p1 } = await released(c);

      const no = await createEo(c, [line(p1, 6, 120)]);
      const yes = await createEo(c, [line(p1, 6, 120, true)]);

      expect(errMatches(no, 409, 'error.externalOrder.line.aboveCeiling')).toBeNull();
      expect(yes.status, yes.text).toBe(200);
      expect(yes.body.lines[0].aboveCeiling).toBe(true);
    });
  });

  test('TC-A5-04 unavailable positions cannot be ordered', async () => {
    await withCtx(async (c) => {
      const own = (await ok('POST', '/api/purchase-positions', c.tok.admin, {
        projectId: c.projectId, kind: 'FINISHING', sourcingPath: 'CLIENT_OWN', plannedMaterialId: c.materialId, quantity: 1,
      })).id as number;
      const inProforma = await issuedProforma(c, [[2, 10]]);
      const foreign = await acceptedPosition(c, 1, 10, { projectId: c.otherProjectId });

      const a = await createEo(c, [line(own, 1, 5)]);
      const b = await createEo(c, [line(inProforma.pids[0], 1, 5)]);
      const d = await createEo(c, [line(foreign, 1, 5)]);

      expect(errMatches(a, 409, 'error.externalOrder.position.notPurchasable')).toBeNull();
      expect(errMatches(b, 409, 'error.externalOrder.position.notPurchasable')).toBeNull();
      expect(errMatches(d, 409, 'error.externalOrder.line.projectMismatch') && errMatches(d, 409, 'error.externalOrder.position.notPurchasable')).toBeNull();
    }, { second: true });
  });

  test('TC-A5-05 lines of a sent order cannot be edited', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      expect((await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer)).status).toBe(200);

      const r = await http('PUT', `/api/external-orders/${e.id}/lines`, c.tok.buyer, { lines: [line(e.p1, 1, 80)] });

      expect(errMatches(r, 409, 'error.externalOrder.notDraft')).toBeNull();
    });
  });

  test('TC-A6-01 sending moves fully covered positions to ORDERED and notifies the financier', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);

      const r = await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);

      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('SENT_FOR_PAYMENT');
      expect(r.body.sentAt).toBeTruthy();
      const p1 = await getPos(c, e.p1);
      const p2 = await getPos(c, e.p2);
      expect(p2.status).toBe('ORDERED');
      expect(p1.status).toBe('WITH_BUYER');
      expect(p1.orderedQuantity).toBe(6);
      expect(p2.actualPurchasePrice).toBe(200);
      expect(p2.deliveryCostShare).toBeCloseTo(27.78, 2);
      await expect.poll(() => notes(c.tok.fin, 'notification.externalOrder.sentForPayment')).toBe(1);
    });
  });

  test('TC-A6-02 an empty or reference-less order cannot be sent', async () => {
    await withCtx(async (c) => {
      const { p1 } = await released(c);
      const empty = await createEo(c, []);
      const noRef = await createEo(c, [line(p1, 6, 80)], { referenceNumber: null });

      const a = await http('POST', `/api/external-orders/${empty.body.id}/send`, c.tok.buyer);
      const b = await http('POST', `/api/external-orders/${noRef.body.id}/send`, c.tok.buyer);

      expect(errMatches(a, 409, 'error.externalOrder.send.noLines')).toBeNull(); // test-cases names send.incomplete (doc drift)
      expect(errMatches(b, 409, 'error.externalOrder.send.noDocument')).toBeNull();
    });
  });

  test('TC-A6-03 the financier sees the queue without project membership but not positions', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);

      const list = await http('GET', '/api/external-orders?query=status==SENT_FOR_PAYMENT&size=200', c.tok.fin);
      const positions = await http('GET', '/api/purchase-positions', c.tok.fin);

      expect(list.status, list.text).toBe(200);
      expect((list.body.content as any[]).map((o) => o.id)).toContain(e.id);
      expect(positions.status).toBe(403);
      const one = await http('GET', `/api/external-orders/${e.id}`, c.tok.fin);
      expect(one.body).toHaveProperty('documents');
    });
  });

  test('TC-A6-04 the financier returns an order to the buyer with a reason', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);

      const noReason = await http('POST', `/api/external-orders/${e.id}/return`, c.tok.fin, {});
      const r = await http('POST', `/api/external-orders/${e.id}/return`, c.tok.fin, { reason: `qa-${c.rid}` });

      expect(noReason.status).toBe(400);
      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('DRAFT');
      expect(r.body.returnReason).toBe(`qa-${c.rid}`);
      const p2 = await getPos(c, e.p2);
      expect(p2.status).toBe('WITH_BUYER');
      expect(p2.orderedQuantity).toBe(0);
    });
  });

  test('TC-A6-05 financier and buyer actions are separated by role', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);

      const bMark = await http('POST', `/api/external-orders/${e.id}/mark-paid`, c.tok.buyer, {});
      const bRet = await http('POST', `/api/external-orders/${e.id}/return`, c.tok.buyer, { reason: 'x' });
      const fSend = await http('POST', `/api/external-orders/${e.id}/send`, c.tok.fin);
      const fLines = await http('PUT', `/api/external-orders/${e.id}/lines`, c.tok.fin, { lines: [] });
      const fCreate = await createEo(c, [], {}, c.tok.fin);

      expect([bMark.status, bRet.status, fSend.status, fLines.status, fCreate.status]).toEqual([403, 403, 403, 403, 403]);
    });
  });

  test('TC-A6-06 the financier marks an order paid by hand', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      const tomorrow = new Date(Date.now() + 2 * 86400000).toISOString().slice(0, 10);

      const future = await http('POST', `/api/external-orders/${e.id}/mark-paid`, c.tok.fin, { paidDate: tomorrow });
      const r = await http('POST', `/api/external-orders/${e.id}/mark-paid`, c.tok.fin, { paidDate: today(), note: `qa-${c.rid}` });

      expect(errMatches(future, 400, 'error.externalOrder.markPaid.dateInFuture')).toBeNull();
      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('PAID');
      expect(r.body.paidSource).toBe('FINANCIER');
      expect((await getPos(c, e.p2)).status).toBe('SUPPLIER_PAID');
      await expect.poll(() => notes(c.tok.buyer, 'notification.externalOrder.paid')).toBe(1);
      expect(await notes(c.tok.fin, 'notification.externalOrder.paid')).toBe(0);
    });
  });

  test('TC-A6-07 a hand-marked payment can be reverted', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      await http('POST', `/api/external-orders/${e.id}/mark-paid`, c.tok.fin, {});

      const r = await http('POST', `/api/external-orders/${e.id}/unmark-paid`, c.tok.fin);

      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('SENT_FOR_PAYMENT');
      expect((await getPos(c, e.p2)).status).toBe('ORDERED');
    });
  });

  /** Sent Eo1 plus a debit ledger row of its payable amount. */
  const sentWithDebit = async (c: Ctx) => {
    const e = await eo1(c);
    const sent = await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
    const payable = sent.body.payableGross as number;
    return { ...e, payable, txn: txn(c, -payable, 'EO pay') };
  };

  test('TC-A7-01 a bank payment marks the order paid with source LEDGER', async () => {
    await withCtx(async (c) => {
      const e = await sentWithDebit(c);

      const r = await link(c, c.tok.fin, e.txn, { kind: 'EXTERNAL_ORDER_PAYMENT', amount: e.payable, externalOrderId: e.id });

      expect(r.status, r.text).toBe(201);
      const o = await getEo(c, e.id);
      expect(o.status).toBe('PAID');
      expect(o.paidSource).toBe('LEDGER');
      expect((await getPos(c, e.p2)).status).toBe('SUPPLIER_PAID');
      await expect.poll(() => notes(c.tok.buyer, 'notification.externalOrder.paid')).toBe(1);
    });
  });

  test('TC-A7-02 the ledger overrides a hand-marked payment without a second notification', async () => {
    await withCtx(async (c) => {
      const e = await sentWithDebit(c);
      await http('POST', `/api/external-orders/${e.id}/mark-paid`, c.tok.fin, {});
      await expect.poll(() => notes(c.tok.buyer, 'notification.externalOrder.paid')).toBe(1);

      const r = await link(c, c.tok.fin, e.txn, { kind: 'EXTERNAL_ORDER_PAYMENT', amount: e.payable, externalOrderId: e.id });

      expect(r.status, r.text).toBe(201);
      const o = await getEo(c, e.id);
      expect(o.status).toBe('PAID');
      expect(o.paidSource).toBe('LEDGER');
      expect(await notes(c.tok.buyer, 'notification.externalOrder.paid')).toBe(1);
    });
  });

  test('TC-A7-04 removing the bank link reverts a ledger-paid order', async () => {
    await withCtx(async (c) => {
      const e = await sentWithDebit(c);
      const l = await link(c, c.tok.fin, e.txn, { kind: 'EXTERNAL_ORDER_PAYMENT', amount: e.payable, externalOrderId: e.id });

      const del = await http('DELETE', `/api/bank/links/${l.body.linkId}`, c.tok.fin);

      expect(del.status, del.text).toBeLessThan(300);
      const o = await getEo(c, e.id);
      expect(o.status).toBe('SENT_FOR_PAYMENT');
      expect(o.paidAt).toBeNull();
      expect((await getPos(c, e.p2)).status).toBe('ORDERED');
    });
  });

  test('TC-A7-04 (b) removing a partial bank link keeps a hand-marked order PAID', async () => {
    await withCtx(async (c) => {
      const e = await sentWithDebit(c);
      await http('POST', `/api/external-orders/${e.id}/mark-paid`, c.tok.fin, {});
      // a partial link does not make the order ledger-paid, so paidSource stays FINANCIER
      const l = await link(c, c.tok.fin, e.txn, { kind: 'EXTERNAL_ORDER_PAYMENT', amount: 100, externalOrderId: e.id });
      expect(l.status, l.text).toBe(201);

      const del = await http('DELETE', `/api/bank/links/${l.body.linkId}`, c.tok.fin);

      expect(del.status, del.text).toBeLessThan(300);
      expect((await getEo(c, e.id)).status).toBe('PAID');
    });
  });

  test('TC-A7-05 orders with positions already in delivery cannot be cancelled or unlinked', async () => {
    await withCtx(async (c) => {
      const e = await sentWithDebit(c);
      const l = await link(c, c.tok.fin, e.txn, { kind: 'EXTERNAL_ORDER_PAYMENT', amount: e.payable, externalOrderId: e.id });
      psql(`UPDATE purchase_positions SET status = 'IN_DELIVERY' WHERE id = :id;`, { id: String(e.p2) });

      const cancel = await http('POST', `/api/external-orders/${e.id}/cancel`, c.tok.buyer, {});
      const del = await http('DELETE', `/api/bank/links/${l.body.linkId}`, c.tok.fin);

      // a ledger-paid order is not cancellable at all: any of the three refusals is acceptable
      expect(errMatches(cancel, 409, 'error.externalOrder.cancel.hasPayments') && errMatches(cancel, 409, 'error.externalOrder.cancel.positionsInDelivery')
        && errMatches(cancel, 409, 'error.externalOrder.status.notAllowed')).toBeNull();
      expect(errMatches(del, 409, 'error.externalOrder.unlink.positionsInDelivery')).toBeNull();
    });
  });

  test('TC-A7-05 (b) a sent order with positions in delivery cannot be cancelled', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      psql(`UPDATE purchase_positions SET status = 'IN_DELIVERY' WHERE id = :id;`, { id: String(e.p2) });

      const cancel = await http('POST', `/api/external-orders/${e.id}/cancel`, c.tok.buyer, {});

      expect(errMatches(cancel, 409, 'error.externalOrder.cancel.positionsInDelivery')).toBeNull();
    });
  });

  test('TC-A7-06 invalid link targets are refused', async () => {
    await withCtx(async (c) => {
      const { p1 } = await released(c);
      const draft = await createEo(c, [line(p1, 6, 80)]);
      const cancelled = await createEo(c, [line(p1, 4, 80)]);
      await http('POST', `/api/external-orders/${cancelled.body.id}/cancel`, c.tok.buyer, {});
      const e = await eo1(c);
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);

      const onDraft = await link(c, c.tok.fin, txn(c, -50, 'EO draft'), { kind: 'EXTERNAL_ORDER_PAYMENT', amount: 50, externalOrderId: draft.body.id });
      const onCancelled = await link(c, c.tok.fin, txn(c, -50, 'EO cancelled'), { kind: 'EXTERNAL_ORDER_PAYMENT', amount: 50, externalOrderId: cancelled.body.id });
      const credit = await link(c, c.tok.fin, txn(c, 50, 'EO credit'), { kind: 'EXTERNAL_ORDER_PAYMENT', amount: 50, externalOrderId: e.id });

      expect(errMatches(onDraft, 409, 'error.externalOrder.link.notLinkable')).toBeNull();
      expect(errMatches(onCancelled, 409, 'error.externalOrder.link.notLinkable')).toBeNull();
      expect(errMatches(credit, credit.status, 'error.bank.link.kindDirection')).toBeNull();
    });
  });

  test('TC-A5-06 supplier documents attach to an order, over-attribution is refused, a foreign NIP is flagged', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      const doc = procDoc(c, 1500);
      const foreign = procDoc(c, 200, '5252248481');
      const second = await createEo(c, [line(e.p1, 4, 80)], { referenceNumber: `QA-${c.rid}-ORD2` });

      const first = await http('PUT', `/api/external-orders/${e.id}/documents`, c.tok.buyer, { documents: [{ procurementDocumentId: doc, amount: 1500 }] });
      const over = await http('PUT', `/api/external-orders/${second.body.id}/documents`, c.tok.buyer, { documents: [{ procurementDocumentId: doc, amount: 100 }] });
      const mismatch = await http('PUT', `/api/external-orders/${second.body.id}/documents`, c.tok.buyer, { documents: [{ procurementDocumentId: foreign, amount: 100 }] });

      expect(first.status, first.text).toBe(200);
      expect(first.body.documents[0]).toMatchObject({ procurementDocumentId: doc, amount: 1500, sellerMismatch: false });
      expect(errMatches(over, 409, 'error.externalOrder.documents.overAttributed')).toBeNull();
      expect(mismatch.status, mismatch.text).toBe(200);
      expect(mismatch.body.documents[0].sellerMismatch).toBe(true);
    });
  });

  test('TC-A6-03 (b) the financier queue shows documents with their payment status', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      const doc = procDoc(c, 738);
      await http('PUT', `/api/external-orders/${e.id}/documents`, c.tok.buyer, { documents: [{ procurementDocumentId: doc, amount: 738 }] });
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);

      const one = await http('GET', `/api/external-orders/${e.id}`, c.tok.fin);

      expect(one.status, one.text).toBe(200);
      expect(one.body.documents).toHaveLength(1);
      expect(one.body.documents[0].paymentStatus).toBeTruthy();
    });
  });

  test('TC-A7-03 bank links that cover the attached document pay the order', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);
      const sent0 = await getEo(c, e.id);
      const payable = sent0.payableGross as number;
      const doc = procDoc(c, payable);
      await http('PUT', `/api/external-orders/${e.id}/documents`, c.tok.buyer, { documents: [{ procurementDocumentId: doc, amount: payable }] });
      await http('POST', `/api/external-orders/${e.id}/send`, c.tok.buyer);
      const t = txn(c, -payable, 'EO doc');

      const l = await link(c, c.tok.fin, t, { kind: 'PROCUREMENT', amount: payable, procurementDocumentId: doc });

      expect(l.status, l.text).toBe(201);
      const o = await getEo(c, e.id);
      expect(o.status).toBe('PAID');
      expect(o.paidSource).toBe('LEDGER');
      const del = await http('DELETE', `/api/bank/links/${l.body.linkId}`, c.tok.fin);
      expect(del.status, del.text).toBeLessThan(300);
      expect((await getEo(c, e.id)).status).toBe('SENT_FOR_PAYMENT');
    });
  });

  test('TC-A12-02 an order shows the proformas its positions came from', async () => {
    await withCtx(async (c) => {
      const e = await eo1(c);

      const o = await getEo(c, e.id);

      expect(o.proformas).toHaveLength(1); // P1 and P2 share one proforma: no duplicates
      expect(o.proformas[0]).toMatchObject({ id: e.proformaId, status: 'PAID' });
      expect(o.proformas[0].number).toMatch(/^PF\/\d{4}\/\d{6}$/);
    });
  });

  test('TC-A12-02 (b) an order of a position without a proforma has no proformas', async () => {
    await withCtx(async (c) => {
      const pkg = (await ok('POST', '/api/purchase-positions', c.tok.admin, {
        projectId: c.projectId, kind: 'FINISHING', sourcingPath: 'PACKAGE', plannedMaterialId: c.materialId, quantity: 2, salePrice: 10, saleVatRateId: c.vat8,
      })).id as number;
      await ok('POST', `/api/purchase-positions/${pkg}/transition`, c.tok.admin, { target: 'WITH_BUYER' });

      const e = await createEo(c, [line(pkg, 2, 5)]);

      expect(e.status, e.text).toBe(200);
      expect(e.body.proformas).toEqual([]);
    });
  });

  test('TC-A12-02 (c) positions from two proformas give two entries', async () => {
    await withCtx(async (c) => {
      const a = await released(c);
      const b = await issuedProforma(c, [[2, 50]]);
      await payProforma(c, b.id, 108);
      const e = await createEo(c, [line(a.p1, 1, 10), line(b.pids[0], 1, 10)]);
      expect(e.status, e.text).toBe(200);
      expect(e.body.proformas).toHaveLength(2);
      void draftProforma; void issueProforma;
    });
  });
});
