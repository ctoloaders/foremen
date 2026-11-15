import { beforeEach, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';

// FOR-10 UI B1 (grid layout and tile states). Source: .kiro/specs/FOR-10-dashboard/test-cases.md.
// Read-only: viewport and network are changed in the browser only.
describe('FOR-10 grid', { tags: ['for-10', 'grid'], session: 'admin' }, () => {
  const columns = (browser: any): Promise<number> =>
    browser.evaluate(() => {
      const grid = document.querySelector('[data-testid="dashboard-grid"]') as HTMLElement;
      return getComputedStyle(grid).gridTemplateColumns.split(' ').filter(Boolean).length;
    });

  // The breakpoint switches on a matchMedia event, so poll until the layout settles.
  const expectColumns = async (browser: any, expected: number) => {
    let actual = -1;
    for (let i = 0; i < 30; i += 1) {
      actual = await columns(browser);
      if (actual === expected) return;
      await new Promise((r) => setTimeout(r, 100));
    }
    expect(actual).toBe(expected);
  };

  test('TC-B1-01 the grid has 4 columns on desktop, 3 on tablet and 1 on phone', async ({ app, screen, browser }) => {
    await browser.setViewport({ width: 1440, height: 900 });
    await app.open('/');
    await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
    await expectColumns(browser, 4);

    await browser.setViewport({ width: 900, height: 900 });
    await expectColumns(browser, 3);

    await browser.setViewport({ width: 400, height: 800 });
    await expectColumns(browser, 1);
  });

  test('TC-B1-01 tile order follows the layout on a phone', async ({ app, screen, browser }) => {
    await browser.setViewport({ width: 400, height: 800 });
    await app.open('/');
    const tiles = screen.getByTestId('dashboard-grid').getByRole('region');
    await expect(tiles.first()).toBeVisible();
    const boxes: Array<{ y: number; x: number } | null> = [];
    for (const tile of await tiles.all()) boxes.push(await tile.boundingBox());
    const ys = boxes.map((b) => b!.y);
    expect([...ys].sort((a, b) => a - b)).toEqual(ys); // tiles are stacked top to bottom in DOM order
  });

  test('TC-B1-02 one failing widget shows an error and does not break the others', async ({ app, screen, browser }) => {
    // Fail every data request of the first placement that asks (the client retries, so one failure is not enough).
    let failingId: string | undefined;
    await browser.route('**/api/dashboard/items/*/data*', async (route: any) => {
      const id = /items\/(\d+)\/data/.exec(route.request.url)?.[1];
      failingId ??= id;
      if (id === failingId) {
        await route.fulfill({ status: 500, json: { message: 'qa injected failure' } });
      } else {
        await route.continue();
      }
    });
    await app.open('/');
    await expect(screen.getByTestId('dashboard-grid')).toBeVisible();

    const grid = screen.getByTestId('dashboard-grid');
    await expect(grid.getByRole('alert')).toContainText('Nie udało się załadować danych', { timeout: 20_000 });
    await expect(grid.getByRole('alert')).toHaveCount(1); // only the failing tile reports an error
    await expect(grid.getByRole('region', 'Użytkownicy wg ról').getByRole('image').first()).toBeVisible();

    // Retry works once the backend answers again.
    await browser.unrouteAll();
    await grid.getByRole('button', 'Ponów').tap();
    await expect(grid.getByRole('alert')).toHaveCount(0);
  });
});
