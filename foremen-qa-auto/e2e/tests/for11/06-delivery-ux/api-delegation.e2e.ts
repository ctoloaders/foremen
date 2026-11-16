import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { del, errMatches, eventually, get, makeWorld, notesOfType, ok, post, psql, uuid, type Tag, type World } from './support.js';
// FOR-11-06 Part A: A11 (delegation of receipt to a WORKER) and A12 (notifications of R13).
// Source: test-cases.md of FOR-11-06. Repeatability: run-id world per test, cleanup in `finally`
// (delegations and goods_receipts are removed by SQL, see support.ts).
const WORKERS: Tag[] = ['WORKER', 'WORKER2', 'WORKERX', 'WORKERI', 'MANAGER2'];
const delegate = (w: World, tag: Tag | 'ADMIN', shipmentId: number, assignee: Tag) =>
  post('/api/goods-receipts/delegations', w.t(tag), { shipmentId, assigneeId: w.users[assignee]!.id });
const receive = (w: World, tag: Tag, positionId: number, shipmentId: number, qty: number) =>
  post('/api/goods-receipts', w.t(tag), { events: [{ positionId, kind: 'ACCEPTED', quantity: qty, shipmentId, clientRequestId: uuid() }] });
const toReceive = async (w: World, tag: Tag) => (await get(`/api/goods-receipts/to-receive?projectId=${w.projectId}`, w.t(tag)));
/** A shipment of 3 units of a fresh SUPPLIER_PAID position. */
async function shipmentOf(w: World, qty = 3) {
  const positionId = w.paidPosition(qty);
  const shipmentId = await w.shipment([[positionId, qty]]);
  return { positionId, shipmentId };
}
describe('FOR-11-06 API: receipt delegation and notifications', { tags: ['for-11-06', 'api'] }, () => {
  test('TC-A11-01 the project manager delegates a shipment to a worker', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { shipmentId } = await shipmentOf(w);
      const r = await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      expect(r.status, r.text).toBe(201);
      expect(r.body).toMatchObject({ shipmentId, assigneeId: w.users.WORKER!.id, status: 'ACTIVE', projectId: w.projectId });
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-02 invalid assignees: other project, inactive member, FOREMAN, CLIENT, BUYER', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { shipmentId } = await shipmentOf(w);
      for (const tag of ['WORKERX', 'WORKERI', 'FOREMAN', 'CLIENT', 'BUYER'] as Tag[]) {
        expect(errMatches(await delegate(w, 'MANAGER', shipmentId, tag), 400, 'error.goodsReceipt.delegation.assigneeInvalid'), tag).toBeNull();
      }
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-03 who may delegate: ADMIN and a member manager; an outside manager and FOREMAN not', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { shipmentId } = await shipmentOf(w);
      expect([403, 404]).toContain((await delegate(w, 'MANAGER2', shipmentId, 'WORKER')).status);
      expect((await delegate(w, 'FOREMAN', shipmentId, 'WORKER')).status).toBe(403);
      expect((await delegate(w, 'ADMIN', shipmentId, 'WORKER')).status).toBe(201);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-04 one active delegation per shipment: re-delegating revokes the previous one', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { shipmentId } = await shipmentOf(w);
      const first = await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      const second = await delegate(w, 'MANAGER', shipmentId, 'WORKER2');
      expect(second.status, second.text).toBe(201);
      const list = await get(`/api/goods-receipts/delegations?shipmentId=${shipmentId}`, w.t('MANAGER'));
      const byId = new Map((list.body.content as any[]).map((d) => [d.id, d.status]));
      expect(byId.get(first.body.id)).toBe('REVOKED');
      expect(byId.get(second.body.id)).toBe('ACTIVE');
      expect(Number(psql(`SELECT COUNT(*) FROM goods_receipt_delegations WHERE shipment_id = ${shipmentId} AND status = 'ACTIVE';`))).toBe(1);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-05 a worker sees only the delegated shipments and their receipts', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const mine = await shipmentOf(w);
      const other = await shipmentOf(w);
      await delegate(w, 'MANAGER', mine.shipmentId, 'WORKER');
      const list = await toReceive(w, 'WORKER');
      expect(list.status, list.text).toBe(200);
      expect((list.body as any[]).map((x) => x.positionId)).toEqual([mine.positionId]);
      expect(JSON.stringify(list.body)).not.toContain(`"shipmentId":${other.shipmentId}`);
      const none = await toReceive(w, 'WORKER2');
      expect(none.status).toBe(200);
      expect(none.body).toEqual([]);
      expect((await receive(w, 'FOREMAN', mine.positionId, mine.shipmentId, 1)).status).toBe(201);
      const events = await get(`/api/goods-receipts?projectId=${w.projectId}`, w.t('WORKER'));
      expect(events.status, events.text).toBe(200);
      expect((events.body.content as any[]).every((e) => e.positionId === mine.positionId)).toBe(true);
      expect((await get(`/api/goods-receipts?projectId=${w.projectId}`, w.t('WORKER2'))).body.content).toEqual([]);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-06 and A11-07 the worker records a receipt on the delegated shipment only; a retry adds nothing', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const mine = await shipmentOf(w);
      const other = await shipmentOf(w);
      const d = await delegate(w, 'MANAGER', mine.shipmentId, 'WORKER');
      const requestId = uuid();
      const body = { events: [{ positionId: mine.positionId, kind: 'ACCEPTED', quantity: 1, shipmentId: mine.shipmentId, clientRequestId: requestId }] };
      const r = await post('/api/goods-receipts', w.t('WORKER'), body);
      expect(r.status, r.text).toBe(201);
      expect(r.body.receipts[0].receivedById).toBe(w.users.WORKER!.id);
      expect(psql(`SELECT delegation_id FROM goods_receipts WHERE id = ${r.body.receipts[0].id};`)).toBe(String(d.body.id));
      const retry = await post('/api/goods-receipts', w.t('WORKER'), body);
      expect(retry.status, retry.text).toBe(201);
      expect(Number(psql(`SELECT COUNT(*) FROM goods_receipts WHERE project_id = ${w.projectId} AND position_id = ${mine.positionId};`))).toBe(1);
      const foreign = await receive(w, 'WORKER', other.positionId, other.shipmentId, 1);
      expect(errMatches(foreign, 403, 'error.goodsReceipt.delegation.required')).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-08 closing the shipment completes the delegation and it leaves the worker list', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { positionId, shipmentId } = await shipmentOf(w, 3);
      const d = await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      expect((await receive(w, 'WORKER', positionId, shipmentId, 3)).status).toBe(201);
      const done = (await get(`/api/goods-receipts/delegations?shipmentId=${shipmentId}`, w.t('MANAGER'))).body.content.find((x: any) => x.id === d.body.id);
      expect(done.status).toBe('COMPLETED');
      expect((await toReceive(w, 'WORKER')).body).toEqual([]);
      expect(psql(`SELECT delegation_id FROM goods_receipts WHERE project_id = ${w.projectId} AND position_id = ${positionId};`)).toBe(String(d.body.id));
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-09 revoking hides the shipment from the worker and keeps the receipts already made', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { positionId, shipmentId } = await shipmentOf(w, 3);
      const d = await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      expect((await receive(w, 'WORKER', positionId, shipmentId, 1)).status).toBe(201);
      const r = await del(`/api/goods-receipts/delegations/${d.body.id}`, w.t('MANAGER'));
      expect(r.status, r.text).toBe(200);
      expect(r.body.status).toBe('REVOKED');
      expect((await toReceive(w, 'WORKER')).body).toEqual([]);
      expect(Number(psql(`SELECT COUNT(*) FROM goods_receipts WHERE position_id = ${positionId};`))).toBe(1);
      expect([403, 404]).toContain((await receive(w, 'WORKER', positionId, shipmentId, 1)).status);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-10 existing rights are unchanged: FOREMAN and MANAGER receive, BUYER reads, FINANCIER and CLIENT 403', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { positionId, shipmentId } = await shipmentOf(w, 4);
      for (const tag of ['FOREMAN', 'MANAGER'] as Tag[]) {
        expect((await toReceive(w, tag)).status, tag).toBe(200);
      }
      expect((await toReceive(w, 'BUYER')).status).toBe(200);
      for (const tag of ['FINANCIER', 'CLIENT'] as Tag[]) expect((await toReceive(w, tag)).status, tag).toBe(403);
      expect((await receive(w, 'FOREMAN', positionId, shipmentId, 1)).status).toBe(201);
      expect((await receive(w, 'MANAGER', positionId, shipmentId, 1)).status).toBe(201);
      await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      const rec = await receive(w, 'WORKER', positionId, shipmentId, 1);
      expect(rec.status, rec.text).toBe(201);
      // a worker cannot void somebody else's receipt
      const foreman = (await get(`/api/goods-receipts?projectId=${w.projectId}`, w.t('MANAGER'))).body.content.find((e: any) => e.receivedById === w.users.FOREMAN!.id);
      const voided = await post(`/api/goods-receipts/${foreman.id}/void`, w.t('WORKER'), { reason: 'qa' });
      expect([403, 404]).toContain(voided.status);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-11 the history names the receiver and the delegator', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { positionId, shipmentId } = await shipmentOf(w);
      await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      expect((await receive(w, 'WORKER', positionId, shipmentId, 1)).status).toBe(201);
      const ev = (await get(`/api/goods-receipts?projectId=${w.projectId}`, w.t('MANAGER'))).body.content[0];
      expect(ev.receivedByName).toContain(w.users.WORKER!.email);
      expect(ev.delegatedByName).toContain(w.users.MANAGER!.email);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A11-12 a cancelled or fully received shipment cannot be delegated (409 shipmentClosed)', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const a = await shipmentOf(w);
      const cancel = await post(`/api/shipments/${a.shipmentId}/cancel`, w.t('BUYER'), {});
      expect(cancel.status, cancel.text).toBeLessThan(300);
      expect(errMatches(await delegate(w, 'MANAGER', a.shipmentId, 'WORKER'), 409, 'error.goodsReceipt.delegation.shipmentClosed')).toBeNull();
      const b = await shipmentOf(w, 2);
      expect((await receive(w, 'MANAGER', b.positionId, b.shipmentId, 2)).status).toBe(201);
      expect(errMatches(await delegate(w, 'MANAGER', b.shipmentId, 'WORKER'), 409, 'error.goodsReceipt.delegation.shipmentClosed')).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A12-01 receipt.delegated reaches the worker with the deep link; the initiator gets nothing', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { shipmentId } = await shipmentOf(w);
      await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      const n = await eventually(async () => {
        const l = await notesOfType(w.t('WORKER'), 'notification.receipt.delegated');
        return l.length ? l : undefined;
      });
      expect(n).toBeDefined();
      expect(n![0]!.deepLink).toBe(`/deliveries/receipt?shipment=${shipmentId}`);
      expect(await notesOfType(w.t('MANAGER'), 'notification.receipt.delegated')).toHaveLength(0);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A12-02 receipt.delegationRevoked reaches the previous worker without a link, on revoke and on re-assignment', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { shipmentId } = await shipmentOf(w);
      const first = await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      await delegate(w, 'MANAGER', shipmentId, 'WORKER2'); // re-assignment revokes the first
      const n = await eventually(async () => {
        const l = await notesOfType(w.t('WORKER'), 'notification.receipt.delegationRevoked');
        return l.length ? l : undefined;
      });
      expect(n).toBeDefined();
      expect(n![0]!.deepLink ?? null).toBeNull();
      const second = (await get(`/api/goods-receipts/delegations?shipmentId=${shipmentId}&status=ACTIVE`, w.t('MANAGER'))).body.content[0];
      expect((await del(`/api/goods-receipts/delegations/${second.id}`, w.t('MANAGER'))).status).toBe(200);
      const m = await eventually(async () => {
        const l = await notesOfType(w.t('WORKER2'), 'notification.receipt.delegationRevoked');
        return l.length ? l : undefined;
      });
      expect(m).toBeDefined();
      expect(first.status).toBe(201);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A12-03 the first receipt of a delegate notifies the delegator; the second one does not', async () => {
    const w = await makeWorld({ tags: WORKERS });
    try {
      const { positionId, shipmentId } = await shipmentOf(w, 3);
      await delegate(w, 'MANAGER', shipmentId, 'WORKER');
      expect((await receive(w, 'WORKER', positionId, shipmentId, 1)).status).toBe(201);
      const n = await eventually(async () => {
        const l = await notesOfType(w.t('MANAGER'), 'notification.receipt.acceptedByDelegate');
        return l.length ? l : undefined;
      });
      expect(n).toBeDefined();
      expect((await receive(w, 'WORKER', positionId, shipmentId, 1)).status).toBe(201);
      await new Promise((res) => setTimeout(res, 1500));
      expect(await notesOfType(w.t('MANAGER'), 'notification.receipt.acceptedByDelegate')).toHaveLength(n!.length);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A12-04 the proforma-paid notification links to the unified payments tab', async () => {
    const w = await makeWorld();
    try {
      const a = await w.acceptedPosition(2, 100);
      const p = await w.issuedPayment([a]);
      if (p.mode !== 'api') test.skip('ENV: PDF storage unavailable (issue answers 502)');
      const gross = Number((await get(`/api/client-payments/${p.id}`, w.t('MANAGER'))).body.amountGross);
      const t = w.statementRow(gross, 'notify');
      expect((await post(`/api/bank/transactions/${t}/links`, w.t('FINANCIER'), { kind: 'PROFORMA_PAYMENT', amount: gross, clientPaymentId: p.id })).status).toBe(201);
      const n = await eventually(async () => {
        const l = await notesOfType(w.t('MANAGER'), 'notification.proforma.paid');
        return l.length ? l : undefined;
      });
      expect(n).toBeDefined();
      expect(n![0]!.deepLink).toBe(`/projects/${w.projectId}/clientPayments?payment=${p.id}`);
      void ok;
    } finally {
      await w.cleanup();
    }
  });
});
