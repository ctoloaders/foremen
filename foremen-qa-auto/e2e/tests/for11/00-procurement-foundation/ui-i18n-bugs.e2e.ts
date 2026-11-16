import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { loginAs } from '../../support/login.js';
import { createWorld, destroyWorld, http, newPosition, newRequest, psql, type World } from './support.js';

// FOR-11-00 Part B: B3 (localization PL <-> RU) and the known planner bug (BUG-1).
// Repeatability: run-id world (fresh BUYER / MANAGER users, so no persisted language of a shared user changes) + teardown.
describe('FOR-11-00 B3 localization', { tags: ['for-11-00', 'ui'] }, () => {
  test('TC-B3-01 seller form and the request-assigned notification follow the UI language', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const req = await newRequest(w);
      await http('PUT', `/api/purchase-requests/${req.body.id}`, w.tok.manager, { assignedBuyerId: w.buyer.id });
      await expect.poll(() => psql(`SELECT COUNT(*) FROM notifications WHERE recipient_id = :u;`, { u: String(w!.buyer.id) }), { timeout: 5000 }).toBe('1');
      await loginAs(app, screen, w.buyer);

      // PL
      await screen.getByTestId('notification-bell').tap();
      await expect(screen.getByText('Przypisano Ci zlecenie zakupu')).toBeVisible();
      await screen.getByTestId('notification-bell').press('Escape').catch(() => undefined);
      await app.open('/material-sellers');
      await screen.getByRole('button', 'Utwórz sprzedawcę').tap();
      await expect(screen.getByRole('button', 'Szukaj po NIP')).toBeVisible();

      // RU
      await screen.getByRole('button', 'Anuluj').tap();
      await screen.getByRole('button', 'PL').tap();
      await screen.getByRole('button', 'Создать продавца').tap();
      await expect(screen.getByRole('button', 'Найти по NIP')).toBeVisible();
      await expect(screen.getByRole('button', 'Szukaj po NIP')).toBeHidden();
      await screen.getByRole('button', 'Отмена').tap();
      await screen.getByTestId('notification-bell').tap();
      await expect(screen.getByText('Вам назначена заявка на закупку')).toBeVisible();
      await expect(screen.getByText('notification.request.assigned')).toBeHidden(); // no raw key
    } finally {
      await destroyWorld(w);
    }
  });
});

describe('FOR-11-00 known planner bug', { tags: ['for-11-00', 'ui'] }, () => {
  // BUG-1 (FINDINGS.md): the "Dodaj pozycję" button of the planner toolbar does nothing: PurchasePlannerPage never passes
  // onAddPosition to PlannerTab and PositionFormSheet (create mode) is not mounted anywhere.
  test('TC-B4-00 (planner) "Dodaj pozycję" opens the create-position form with the seller picker', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await newPosition(w);
      await loginAs(app, screen, w.manager);
      await app.open(`/deliveries?project=${w.projA}`);
      await screen.getByTestId('add-position').tap();
      await expect(screen.getByRole('dialog').filter({ has: screen.getByTestId('material-picker') })).toBeVisible();
    } finally {
      await destroyWorld(w);
    }
  });
});
