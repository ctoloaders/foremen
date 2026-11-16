import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, dayShift, setupEnv, teardownEnv, uiLogin, type Dataset, type Env } from './support.js';

// FOR-11-01 Part B: B7 deep link of the request.assigned notification.
// Repeatability: run-id dataset created in beforeAll, removed in afterAll.
describe('FOR-11-01 UI request deep link', { tags: ['for-11-01', 'ui'] }, () => {
  let env: Env;
  let ds: Dataset;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });

  test('TC-B7-01 the bell notification opens the planner on the request group, expanded and highlighted', async ({ app, screen, browser }) => {
    const pos = await api('POST', '/api/purchase-positions', env.tok.manager, {
      projectId: env.projectA, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: 40, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-dl`,
    });
    const req = await api('POST', '/api/purchase-requests/from-positions', env.tok.manager, {
      positionIds: [pos.body.id], referenceNumber: `QA-${env.id}-DL`, expectedDeliveryDate: dayShift(9), assignedBuyerId: env.buyer2.id,
    });
    expect(req.status, JSON.stringify(req.body)).toBe(200);
    await uiLogin(app, screen, env.buyer2);
    await screen.getByTestId('notification-bell').tap();
    await expect(screen.getByTestId('notification-list')).toBeVisible();
    await screen.getByTestId(/^notification-link-\d+$/).first().tap();
    await expect(browser).toHaveURL(new RegExp(`/deliveries\\?request=${req.body.id}`));
    await expect(screen.getByTestId('planner-tab-body')).toBeVisible();
    await expect(screen.getByTestId(`planner-group-${req.body.id}`, { visible: true })).toHaveAttribute('data-highlighted', 'true');
    await expect(screen.getByTestId(`planner-row-${pos.body.id}`)).toBeVisible(); // expanded
  });

  test('TC-B7-01 an unknown request id shows the "not found" message and the page keeps working', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open('/deliveries?request=999999999');
    await expect(screen.getByTestId('request-not-found')).toContainText('Nie znaleziono zlecenia lub brak dostępu');
    await expect(screen.getByTestId('slice-bar')).toBeVisible();
  });

  test('TC-B7-01 a request of a foreign project gives the same message (no existence leak)', async ({ app, screen }) => {
    const pos = await api('POST', '/api/purchase-positions', env.tok.admin, {
      projectId: env.projectB, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: 40, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-dlB`,
    });
    const req = await api('POST', '/api/purchase-requests/from-positions', env.tok.admin, {
      positionIds: [pos.body.id], referenceNumber: `QA-${env.id}-DLB`,
    });
    expect(req.status, JSON.stringify(req.body)).toBe(200);
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?request=${req.body.id}`);
    await expect(screen.getByTestId('request-not-found')).toContainText('Nie znaleziono zlecenia lub brak dostępu');
  });
});
