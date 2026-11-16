import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { get, makeWorld, post, psql, type Tag, type World } from './support.js';
import { openPath, uiLogin } from './ui-support.js';

// FOR-11-06 Part B (UI): B6 delegation of goods receipt to a project WORKER (manager and worker screens).
// Requirement 12. Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-06-delivery-ux-refinements/test-cases.md.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup() removes delegations and receipts) in `finally`.
// Labels follow the PL locale: "Przekaż odbiór" (delegate), "Cofnij" (revoke), "Przekazano: {name}" (chip).

const TAGS: Tag[] = ['WORKER', 'WORKER2'];

/** A shipment of `qty` units of a fresh SUPPLIER_PAID position. */
async function shipmentOf(w: World, qty = 3) {
  const positionId = w.paidPosition(qty);
  const shipmentId = await w.shipment([[positionId, qty]]);
  return { positionId, shipmentId };
}
const delegate = (w: World, shipmentId: number, assignee: Tag) =>
  post('/api/goods-receipts/delegations', w.t('MANAGER'), { shipmentId, assigneeId: w.users[assignee]!.id });

describe('FOR-11-06 UI: receipt delegation', { tags: ['for-11-06', 'ui', 'delegation'] }, () => {
  test('TC-B6-01 MANAGER delegates a shipment to a project worker and the chip appears in the shipments table', async ({ app, screen, browser }) => {
    const w = await makeWorld({ tags: [...TAGS, 'WORKERX'] });
    try {
      const { shipmentId } = await shipmentOf(w);
      await uiLogin(app, screen, w.users.MANAGER!);
      await openPath(app, screen, browser, w.projectId, `/deliveries/shipments/${shipmentId}`);
      await screen.getByTestId(`delegate-open-${shipmentId}`).first().tap();
      const dialog = screen.getByTestId('delegate-receipt-dialog');
      await expect(dialog).toBeVisible();
      await expect(dialog.getByTestId(`delegate-worker-${w.users.WORKER!.id}`)).toBeVisible();
      await expect(dialog.getByTestId(`delegate-worker-${w.users.WORKER2!.id}`)).toBeVisible();
      // Only workers of this project are offered.
      await expect(dialog.getByTestId(`delegate-worker-${w.users.WORKERX!.id}`)).toBeHidden();
      await dialog.getByTestId(`delegate-worker-${w.users.WORKER!.id}`).check();
      await dialog.getByTestId('delegate-submit').tap();
      await expect(screen.getByTestId('delegated-chip').first()).toContainText('Przekazano:');
      await expect.poll(() => psql(`SELECT status FROM goods_receipt_delegations WHERE shipment_id = ${shipmentId};`)).toBe('ACTIVE');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B6-02 WORKER at 390 px sees "Odbiór dostaw" with only the delegated shipment; without delegations an empty state', async ({ app, screen, browser }) => {
    const w = await makeWorld({ tags: TAGS });
    try {
      const mine = await shipmentOf(w);
      const foreign = await shipmentOf(w);
      expect((await delegate(w, mine.shipmentId, 'WORKER')).status).toBe(201);
      await browser.setViewport({ width: 390, height: 800 });
      await uiLogin(app, screen, w.users.WORKER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries/receipt');
      await expect(screen.getByRole('heading', 'Odbiór dostaw')).toBeVisible();
      await expect(screen.getByTestId(`receipt-card-${mine.positionId}`)).toBeVisible();
      await expect(screen.getByTestId(`receipt-card-${foreign.positionId}`)).toBeHidden();
      await app.clearState();
      // WORKER2 has no delegations: the empty state, no cards.
      await uiLogin(app, screen, w.users.WORKER2!);
      await openPath(app, screen, browser, w.projectId, '/deliveries/receipt');
      await expect(screen.getByText('Nie przekazano Ci żadnych dostaw')).toBeVisible();
      await expect(screen.getByTestId(`receipt-card-${mine.positionId}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B6-03 WORKER accepts part of the shipment and the manager history reads "Przyjął {name} (przekazał {name})"', async ({ app, screen, browser }) => {
    const w = await makeWorld({ tags: TAGS });
    try {
      const { positionId, shipmentId } = await shipmentOf(w);
      expect((await delegate(w, shipmentId, 'WORKER')).status).toBe(201);
      await browser.setViewport({ width: 390, height: 800 });
      await uiLogin(app, screen, w.users.WORKER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries/receipt');
      await screen.getByTestId(`receive-btn-${positionId}`).tap();
      await expect(screen.getByTestId('receive-sheet')).toBeVisible();
      await screen.getByRole('button', 'Zapisz').tap();
      await expect.poll(() => Number(psql(`SELECT COUNT(*) FROM goods_receipts WHERE position_id = ${positionId} AND delegation_id IS NOT NULL;`))).toBeGreaterThan(0);
      await app.clearState();
      await browser.setViewport({ width: 1280, height: 900 });
      await uiLogin(app, screen, w.users.MANAGER!);
      await openPath(app, screen, browser, w.projectId, `/deliveries/receipt?position=${positionId}`);
      await screen.getByRole('tab', 'Historia').tap();
      await expect(screen.getByText(/Przyjął .* \(przekazał .*\)/).first()).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B6-04 revoking the delegation removes the shipment from the worker and the notification carries no link', async ({ app, screen, browser }) => {
    const w = await makeWorld({ tags: TAGS });
    try {
      const { positionId, shipmentId } = await shipmentOf(w);
      const d = await delegate(w, shipmentId, 'WORKER');
      expect(d.status, d.text).toBe(201);
      await uiLogin(app, screen, w.users.MANAGER!);
      await openPath(app, screen, browser, w.projectId, `/deliveries/shipments/${shipmentId}`);
      await screen.getByTestId(`delegate-revoke-${shipmentId}`).first().tap();
      await expect.poll(() => psql(`SELECT status FROM goods_receipt_delegations WHERE id = ${d.body.id};`)).not.toBe('ACTIVE');
      await app.clearState();
      await uiLogin(app, screen, w.users.WORKER!);
      await openPath(app, screen, browser, w.projectId, '/deliveries/receipt');
      await expect(screen.getByTestId(`receipt-card-${positionId}`)).toBeHidden();
      const notes = (await get('/api/notifications?size=100', w.t('WORKER'))).body;
      const list: Array<{ type: string; deepLink: string | null }> = Array.isArray(notes) ? notes : (notes?.content ?? []);
      const revoked = list.find((n) => n.type === 'notification.receipt.delegationRevoked');
      expect(revoked, 'no revocation notification').toBeTruthy();
      expect(revoked!.deepLink ?? null).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
});
