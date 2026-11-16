import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { loginAs } from '../../support/login.js';
import { createWorld, destroyWorld, http, isoDate, newPosition, newRequest, type World } from './support.js';

// FOR-11-00 Part B, B5 (procurement widgets on the dashboard). Repeatability: run-id world + teardown.
async function seed(w: World) {
  const req = await newRequest(w, { expectedDeliveryDate: isoDate(3) });
  const p = await newPosition(w, { sourcingPath: 'PACKAGE' });
  await http('POST', `/api/purchase-requests/${req.body.id}/positions`, w.tok.manager, { positionIds: [p.body.id] });
  await http('POST', `/api/purchase-positions/${p.body.id}/transition`, w.tok.manager, { target: 'WITH_BUYER' });
}

describe('FOR-11-00 B5 dashboard widgets', { tags: ['for-11-00', 'ui', 'dashboard'] }, () => {
  test('TC-B5-01 the FOREMAN sees "Najbliższe dostawy" with his delivery and a click opens /deliveries', async ({ app, screen, browser }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await seed(w);
      await loginAs(app, screen, w.foreman);
      const tile = screen.getByTestId('dashboard-grid').getByText('Najbliższe dostawy');
      await expect(tile).toBeVisible();
      const row = screen.getByRole('row').filter({ hasText: `QA-MAT-${w.id}-A` });
      await expect(row).toBeVisible();
      await expect(row).toContainText(w.nameA);
      await expect(row).toContainText('3');
    } finally {
      await destroyWorld(w);
    }
  });

  // BUG-2 (FINDINGS.md): widget links (ROW/KPI -> /deliveries) are never applied: DashboardGrid is not given `configs`.
  test('TC-B5-01 (click) a click on the delivery row opens /deliveries', async ({ app, screen, browser }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await seed(w);
      await loginAs(app, screen, w.foreman);
      const row = screen.getByRole('row').filter({ hasText: `QA-MAT-${w.id}-A` });
      await expect(row).toBeVisible();
      await row.tap();
      await expect(browser).toHaveURL(/\/deliveries/);
    } finally {
      await destroyWorld(w);
    }
  });

  // BUG-3 (FINDINGS.md): the delivery status is shown as the raw enum "WITH_BUYER" instead of the localized stage name.
  test('TC-B5-01 (status) the status column shows the localized stage "U zakupowca"', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await seed(w);
      await loginAs(app, screen, w.foreman);
      const row = screen.getByRole('row').filter({ hasText: `QA-MAT-${w.id}-A` });
      await expect(row).toContainText('U zakupowca');
      await expect(row).not.toContainText('WITH_BUYER');
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-B5-02 the BUYER dashboard shows the three procurement widgets; the KPI equals the WITH_BUYER count from the API', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await seed(w);
      await loginAs(app, screen, w.buyer);
      const grid = screen.getByTestId('dashboard-grid');
      for (const title of ['U kupca', 'Zakupy wg etapów', 'Najbliższe dostawy']) await expect(grid.getByText(title)).toBeVisible();
      const api = await http('GET', `/api/purchase-positions?query=${encodeURIComponent('status==WITH_BUYER')}&size=1`, w.tok.buyer);
      await expect(screen.getByRole('group', `U kupca: Liczba: ${api.body.totalElements}`)).toBeVisible();
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-B5-04 the widget titles follow the UI language (PL -> RU)', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      await seed(w);
      await loginAs(app, screen, w.foreman);
      await expect(screen.getByText('Najbliższe dostawy')).toBeVisible();
      await screen.getByRole('button', 'PL').tap();
            await expect(screen.getByText('Ближайшие поставки')).toBeVisible();
      await expect(screen.getByText('Najbliższe dostawy')).toBeHidden();
    } finally {
      await destroyWorld(w);
    }
  });
});
