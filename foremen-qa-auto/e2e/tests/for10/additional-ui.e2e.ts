import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  addItem, clearDashboard, createProject, deleteProject, fillDashboard, layoutOf, roleIdByCode, tokenOf, widgetIdByCode,
} from '../support/api.js';
import { loginAs } from '../support/login.js';
import { createUser, deleteUser, runId, type TestUser } from '../support/users.js';

// FOR-10 additional UI cases (TC-DX-B*). Source: .kiro/specs/FOR-10-dashboard/additional-test-cases.md.
// Repeatability: every test makes its own ADMIN-role user (sees every widget) with a layout prepared
// through the API; the user is deleted in `finally`, which cascades its placements.

/** Records the JSON body of every POST /api/dashboard/items the page sends. */
async function captureAdds(browser: any): Promise<Array<Record<string, unknown>>> {
  const bodies: Array<Record<string, unknown>> = [];
  await browser.route('**/api/dashboard/items', async (route: any) => {
    if (route.request.method === 'POST') bodies.push(JSON.parse(route.request.postData ?? '{}'));
    await route.continue();
  });
  return bodies;
}

async function withLayout(
  cells: Array<[string, number]>,
  body: (user: TestUser, token: string) => Promise<void>,
): Promise<void> {
  const user = createUser('ADMIN', 'ui');
  try {
    await clearDashboard(user);
    const token = await tokenOf(user);
    for (const [code, cellIndex] of cells) await addItem(token, { widgetId: await widgetIdByCode(token, code), cellIndex });
    await body(user, token);
  } finally {
    deleteUser(user);
  }
}

describe('FOR-10 additional UI', { tags: ['for-10', 'additional'] }, () => {
  test('TC-DX-B01 an Add_Slot opens the picker for its own cell and adds there', async ({ app, screen, browser }) => {
    await withLayout([['PAYMENTS_RECEIVED', 0]], async (user, token) => {
      await loginAs(app, screen, user);
      const adds = await captureAdds(browser);

      await screen.getByTestId('dashboard-add-slot-6').tap(); // row 2, column 3
      await expect(screen.getByTestId('dashboard-picker-position')).toContainText('Wiersz 2, kolumna 3');
      await screen.getByTestId(`dashboard-picker-item-${await widgetIdByCode(token, 'BANK_PENDING')}`).tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();

      expect(adds.at(-1)?.cellIndex).toBe(6);
      expect((await layoutOf(token)).find((i) => i.code === 'BANK_PENDING')?.cellIndex).toBe(6);
    });
  });

  test('TC-DX-B02 the header button opens the picker without a position and adds first-fit', async ({ app, screen, browser }) => {
    // Rows 0-1 are blocked for a 4-wide widget; a 4x3 widget fits only from cell 8.
    await withLayout([['PAYMENTS_RECEIVED', 0], ['BANK_PENDING', 5]], async (user, token) => {
      await loginAs(app, screen, user);
      const adds = await captureAdds(browser);

      await screen.getByTestId('dashboard-add').tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeVisible();
      await expect(screen.getByTestId('dashboard-picker-position')).toHaveCount(0);
      const big = screen.getByTestId(`dashboard-picker-item-${await widgetIdByCode(token, 'ACTIVE_PROJECTS')}`);
      await expect(big).toBeEnabled();
      await big.tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();

      expect(adds.at(-1)).not.toHaveProperty('cellIndex');
      expect((await layoutOf(token)).find((i) => i.code === 'ACTIVE_PROJECTS')?.cellIndex).toBe(8);
    });
  });

  test('TC-DX-B03 a full grid: the header picker says there is no free space', async ({ app, screen }) => {
    const user = createUser('ADMIN', 'full');
    try {
      await fillDashboard(user);
      await loginAs(app, screen, user);
      await screen.getByTestId('dashboard-add').tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toContainText('Brak wolnego miejsca');
    } finally {
      deleteUser(user);
    }
  });

  test('TC-DX-B04 after a project widget is added the library opens on the widget list again', async ({ app, screen }) => {
    const id = runId();
    let manager: TestUser | undefined;
    let client: TestUser | undefined;
    let projectId: number | undefined;
    try {
      manager = createUser('MANAGER', 'mgr');
      client = createUser('CLIENT', 'cl');
      projectId = await createProject(`DASH-${id}-A`, [
        { userId: manager.id, projectRoleId: await roleIdByCode('MANAGER') },
        { userId: client.id, projectRoleId: await roleIdByCode('CLIENT') },
      ]);
      await clearDashboard(manager);
      await loginAs(app, screen, manager);

      await screen.getByTestId('dashboard-add').tap();
      await screen.getByTestId('dashboard-picker-item-3').tap(); // "Karta projektu"
      const picker = screen.getByTestId('dashboard-project-picker');
      await picker.getByRole('checkbox', 'Tylko aktywne').uncheck();
      await picker.getByRole('textbox', 'Szukaj').fill(`DASH-${id}-A`);
      await picker.getByText(`DASH-${id}-A`).first().tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();

      await screen.getByTestId('dashboard-add').tap();
      await expect(screen.getByTestId('dashboard-picker-search')).toBeVisible();
      await expect(screen.getByTestId('dashboard-project-picker')).toHaveCount(0);
    } finally {
      if (projectId) await deleteProject(projectId);
      deleteUser(manager);
      deleteUser(client);
    }
  });

  test('TC-DX-B05 the picker search is cleared when the picker is reopened', async ({ app, screen }) => {
    await withLayout([], async (user) => {
      await loginAs(app, screen, user);
      await screen.getByTestId('dashboard-add').tap();
      await screen.getByTestId('dashboard-picker-search').fill('Fundusz');
      await screen.getByTestId('dashboard-picker-search').press('Escape');
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();
      await screen.getByTestId('dashboard-add').tap();
      await expect(screen.getByTestId('dashboard-picker-search')).toHaveValue('');
    });
  });

  test('TC-DX-B06 in the PL UI the tile controls are named in Polish', async ({ app, screen }) => {
    await withLayout([['PAYMENTS_RECEIVED', 0], ['USERS_BY_ROLE', 1]], async (user) => {
      await loginAs(app, screen, user);
      const selects = screen.getByTestId(/^dashboard-tile-style-\d+$/);
      await expect(selects.first()).toBeVisible();
      for (const s of await selects.all()) {
        const name = (await s.getAttribute('aria-label')) ?? '';
        expect(name, name).not.toMatch(/[А-Яа-яЁё]/);
      }
    });
  });

  test('TC-DX-B07 removing the last widget shows the empty state and the slot at cell 0', async ({ app, screen }) => {
    await withLayout([['PAYMENTS_RECEIVED', 5]], async (user) => {
      await loginAs(app, screen, user);
      await screen.getByRole('button', 'Usuń: Otrzymane płatności').tap();
      await screen.getByTestId('dashboard-remove-confirm').tap();
      await expect(screen.getByTestId('dashboard-empty')).toBeVisible();
      await expect(screen.getByTestId('dashboard-add-slot-0')).toBeVisible();
    });
  });

  test('TC-DX-B08 a failed remove keeps the dialog open with an error and the tile stays', async ({ app, screen, browser }) => {
    await withLayout([['PAYMENTS_RECEIVED', 0]], async (user, token) => {
      await loginAs(app, screen, user);
      await browser.route('**/api/dashboard/items/*', async (route: any) => {
        if (route.request.method === 'DELETE') await route.fulfill({ status: 500, json: { message: 'qa' } });
        else await route.continue();
      });
      await screen.getByRole('button', 'Usuń: Otrzymane płatności').tap();
      await screen.getByTestId('dashboard-remove-confirm').tap();
      await expect(screen.getByTestId('dashboard-remove-dialog').getByRole('alert')).toBeVisible();
      await screen.getByRole('button', 'Anuluj').tap();
      await expect(screen.getByRole('region', 'Otrzymane płatności')).toBeVisible();
      expect((await layoutOf(token)).length).toBe(1);
    });
  });

  test('TC-DX-B09 a rejected add shows the error in the picker and creates no tile', async ({ app, screen, browser, }) => {
    await withLayout([], async (user, token) => {
      await loginAs(app, screen, user);
      await browser.route('**/api/dashboard/items', async (route: any) => {
        if (route.request.method === 'POST') {
          await route.fulfill({ status: 409, json: { status: 409, code: 'error.dashboard.cell.occupied', message: 'Ta komórka jest zajęta.' } });
        } else await route.continue();
      });
      await screen.getByTestId('dashboard-add').tap();
      await screen.getByTestId(`dashboard-picker-item-${await widgetIdByCode(token, 'PAYMENTS_RECEIVED')}`).tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeVisible();
      await expect(screen.getByTestId('dashboard-widget-picker').getByRole('alert')).toBeVisible();
      await expect(screen.getByRole('region', 'Otrzymane płatności')).toHaveCount(0);
      expect((await layoutOf(token)).length).toBe(0);
    });
  });

  test('TC-DX-B10 a failed layout load shows an error and Retry recovers', async ({ app, screen, browser }) => {
    await withLayout([['PAYMENTS_RECEIVED', 0]], async (user) => {
      let fail = true;
      await browser.route('**/api/dashboard', async (route: any) => {
        if (fail && route.request.method === 'GET') await route.fulfill({ status: 500, json: { message: 'qa' } });
        else await route.continue();
      });
      await app.open('/login');
      await screen.getByRole('textbox', 'E-mail').fill(user.email);
      await screen.getByRole('textbox', 'Hasło').fill(user.password);
      await screen.getByRole('button', 'Zaloguj się').tap();
      await expect(screen.getByTestId('dashboard-error')).toBeVisible({ timeout: 20_000 });
      fail = false;
      await screen.getByTestId('dashboard-error').getByRole('button').tap();
      await expect(screen.getByRole('region', 'Otrzymane płatności')).toBeVisible();
    });
  });

  test('TC-DX-B11 a MANAGER is offered only the projects he is a member of', async ({ app, screen }) => {
    const id = runId();
    let manager: TestUser | undefined;
    let client: TestUser | undefined;
    let projectId: number | undefined;
    try {
      manager = createUser('MANAGER', 'mgr');
      client = createUser('CLIENT', 'cl');
      projectId = await createProject(`DASH-${id}-OWN`, [
        { userId: manager.id, projectRoleId: await roleIdByCode('MANAGER') },
        { userId: client.id, projectRoleId: await roleIdByCode('CLIENT') },
      ]);
      await clearDashboard(manager);
      await loginAs(app, screen, manager);
      await screen.getByTestId('dashboard-add').tap();
      await screen.getByTestId('dashboard-picker-item-3').tap();
      const picker = screen.getByTestId('dashboard-project-picker');
      await picker.getByRole('checkbox', 'Tylko aktywne').uncheck();
      await expect(picker.getByTestId(/^dashboard-project-\d+$/)).toHaveCount(1);
      await expect(picker.getByTestId(`dashboard-project-${projectId}`)).toBeVisible();
    } finally {
      if (projectId) await deleteProject(projectId);
      deleteUser(manager);
      deleteUser(client);
    }
  });

  test('TC-DX-B12 the date range of one user is not inherited by the next user in the same tab', async ({ app, screen }) => {
    const a = createUser('ADMIN', 'a');
    const b = createUser('ADMIN', 'b');
    try {
      await loginAs(app, screen, a);
      await screen.getByTestId('dashboard-quick-period').tap();
      await screen.getByRole('option', 'Poprzedni').tap();
      const changed = (await screen.getByRole('button', 'Od').textContent()) ?? '';

      await screen.getByRole('button', `QA ${a.email}`).tap();
      await screen.getByRole('menuitem', 'Wyloguj się').tap();
      await loginAs(app, screen, b);
      await expect(screen.getByRole('button', 'Od')).not.toHaveText(changed);
    } finally {
      deleteUser(a);
      deleteUser(b);
    }
  });
});
