import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { createRoleWithGrants, deleteRole, fillDashboard, setRoleGrants } from '../support/api.js';
import { loginAs } from '../support/login.js';
import { createUser, deleteUser, runId, type TestUser } from '../support/users.js';

// FOR-10 UI B1/B3 for non-admin roles. Source: .kiro/specs/FOR-10-dashboard/test-cases.md.
// Repeatability: every test creates its own user (run-id in the e-mail) and deletes it in `finally`.
describe('FOR-10 Panel by role', { tags: ['for-10', 'roles'] }, () => {
  test('TC-B1-03 a user without widgets sees the empty state and the add tile', async ({ app, screen }) => {
    // A custom role gets no default widgets (TC-A8-03), so the layout starts empty.
    const role = await createRoleWithGrants(`DASH_${runId().toUpperCase()}`, []);
    let user: TestUser | undefined;
    try {
      user = createUser(role.code, 'empty');
      await loginAs(app, screen, user);

      await expect(screen.getByTestId('dashboard-grid').getByRole('region')).toHaveCount(0);
      await expect(screen.getByTestId('dashboard-add')).toBeVisible();
      await expect(screen.getByTestId('dashboard-add-slot-0')).toBeVisible();
    } finally {
      deleteUser(user);
      await deleteRole(role.id);
    }
  });

  test('TC-B3-02 a WORKER sees payroll widgets as disabled with the missing resource named', async ({ app, screen }) => {
    const user = createUser('WORKER', 'worker');
    try {
      await loginAs(app, screen, user);
      await screen.getByTestId('dashboard-add').tap();
      await screen.getByTestId('dashboard-picker-search').fill('Fundusz płac');

      const item = screen.getByTestId('dashboard-picker-item-10');
      await expect(item).toBeDisabled();
      await expect(item).toContainText('Brak dostępu do danych: PAYROLL');

      await screen.getByTestId('dashboard-picker-search').press('Escape');
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();
      await expect(screen.getByRole('region', 'Fundusz płac')).toHaveCount(0);
    } finally {
      deleteUser(user);
    }
  });

  test('TC-B3-05 a full grid says so and nothing can be added', async ({ app, screen }) => {
    const user = createUser('ADMIN', 'full'); // ADMIN sees every widget, enough to fill all 36 cells
    try {
      await fillDashboard(user);
      await loginAs(app, screen, user);

      await expect(screen.getByTestId('dashboard-grid-full')).toHaveText('Siatka jest pełna');
      await expect(screen.getByRole('button', /^Dodaj widżet: /)).toHaveCount(0); // no Add_Slots left

      await screen.getByTestId('dashboard-add').tap();
      await expect(screen.getByTestId(/^dashboard-picker-item-/)).toHaveCount(0); // nothing to pick
      // The message itself ("no space", Req 3.3) is checked by TC-DX-B03 (BUG-3).
    } finally {
      deleteUser(user); // cascades the placements
    }
  });

  test('TC-B3-06 losing access greys the tile out and restoring it brings the data back', async ({ app, screen, browser }) => {
    const role = await createRoleWithGrants(`DASH_${runId().toUpperCase()}`, [['PAYMENTS', 'READ']]);
    let user: TestUser | undefined;
    try {
      user = createUser(role.code, 'matrix');
      await loginAs(app, screen, user);

      await screen.getByTestId('dashboard-add').tap();
      await screen.getByTestId('dashboard-picker-item-8').tap(); // "Otrzymane płatności", needs PAYMENTS
      const tile = screen.getByRole('region', 'Otrzymane płatności');
      await expect(tile).toBeVisible();
      await expect(tile.getByRole('group')).toBeVisible(); // KPI value rendered

      // The admin revokes the grant; after a reload the tile is disabled and loads no data.
      await setRoleGrants(role.id, []);
      await browser.reload();
      await expect(screen.getByTestId('dashboard-grid')).toBeVisible();
      await expect(tile).toContainText('Brak dostępu do danych widżetu');
      await expect(tile.getByRole('group')).toHaveCount(0);
      await expect(tile.getByRole('button', 'Usuń: Otrzymane płatności')).toBeVisible(); // can still be removed

      // The grant comes back: the same placement works again, no re-adding needed.
      await setRoleGrants(role.id, [['PAYMENTS', 'READ']]);
      await browser.reload();
      await expect(tile.getByRole('group')).toBeVisible();
    } finally {
      deleteUser(user);
      await deleteRole(role.id);
    }
  });
});
