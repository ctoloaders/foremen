import { beforeEach, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';

// FOR-10 dashboard, Part B (UI). Source: .kiro/specs/FOR-10-dashboard/test-cases.md.
// Repeatability: tests that add a widget remove it again in the same test (teardown);
// the seeded ADMIN placements are never changed.
describe('FOR-10 Panel', { tags: ['for-10', 'panel'], session: 'admin' }, () => {
  beforeEach(async ({ app, screen }) => {
    await app.open('/');
    await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
  });

  test('TC-B1-01 shows the widget grid with the toolbar', async ({ screen }) => {
    await expect(screen.getByRole('heading', 'Panel').first()).toBeVisible();
    await expect(screen.getByTestId('dashboard-add')).toBeVisible();
    await expect(screen.getByTestId('dashboard-appearance-open')).toBeVisible();
    await expect(screen.getByTestId('dashboard-date-filter')).toBeVisible();
    await expect(screen.getByTestId('dashboard-grid').getByRole('region').first()).toBeVisible();
  });

  test('TC-B2-01 date filter defaults to the current month', async ({ screen }) => {
    const now = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    const last = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate();
    const from = `01.${pad(now.getMonth() + 1)}.${now.getFullYear()}`;
    const to = `${pad(last)}.${pad(now.getMonth() + 1)}.${now.getFullYear()}`;

    await expect(screen.getByRole('button', 'Od')).toHaveText(from);
    await expect(screen.getByRole('button', 'Do')).toHaveText(to);
    await expect(screen.getByTestId('dashboard-quick-period')).toBeVisible();
    await expect(screen.getByTestId('dashboard-quick-unit')).toBeVisible();
  });

  test('TC-B3-01 adds a widget from the library and TC-B3-04 removes it with confirmation', async ({ screen }) => {
    const tile = screen.getByRole('region', 'Otrzymane płatności');
    const removeButton = screen.getByRole('button', 'Usuń: Otrzymane płatności');
    const removeWidget = async () => {
      await removeButton.tap();
      await screen.getByTestId('dashboard-remove-confirm').tap();
      await expect(tile).toHaveCount(0);
    };

    // Setup: a leftover from an aborted earlier run must not break the precondition.
    if (await tile.isVisible()) await removeWidget();
    await expect(tile).toHaveCount(0);

    try {
      // B3-01: open the library, pick a 1x1 KPI widget.
      await screen.getByTestId('dashboard-add').tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeVisible();
      await screen.getByTestId('dashboard-picker-search').fill('Otrzymane');
      await screen.getByTestId('dashboard-picker-item-8').tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();
      await expect(tile).toBeVisible();

      // B3-04: the dialog names the widget; Cancel keeps it.
      await removeButton.tap();
      await expect(screen.getByTestId('dashboard-remove-dialog')).toBeVisible();
      await expect(screen.getByTestId('dashboard-remove-dialog')).toContainText('Otrzymane płatności');
      await screen.getByRole('button', 'Anuluj').tap();
      await expect(screen.getByTestId('dashboard-remove-dialog')).toBeHidden();
      await expect(tile).toBeVisible();

      // B3-04: Confirm removes it.
      await removeWidget();
    } finally {
      // Teardown: leave the ADMIN layout as it was, even when an assertion above failed.
      if (await tile.isVisible()) await removeWidget();
    }
  });
});
