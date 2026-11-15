import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { clearDashboard, createProject, deleteProject, roleIdByCode } from '../support/api.js';
import { loginAs } from '../support/login.js';
import { createUser, deleteUser, runId, type TestUser } from '../support/users.js';

// FOR-10 UI B3-03 (project widget). Source: .kiro/specs/FOR-10-dashboard/test-cases.md.
// Repeatability: a fresh MANAGER (layout cleared through the API so it is deterministic) and a CLIENT
// are members of two projects named with the run-id. Everything is deleted in `finally`.
describe('FOR-10 project widget', { tags: ['for-10', 'project-widget'] }, () => {
  test('TC-B3-03 a project widget is added per project and not twice for the same one', async ({ app, screen }) => {
    const id = runId();
    const nameA = `DASH-${id}-A`;
    const nameB = `DASH-${id}-B`;
    const projectIds: number[] = [];
    let manager: TestUser | undefined;
    let client: TestUser | undefined;
    const grid = screen.getByTestId('dashboard-grid');
    const tileOf = (name: string) => grid.getByTestId('dashboard-tile-project').filter({ hasText: name });

    // Each add starts from a freshly loaded page; the picker reset itself is asserted by TC-DX-B04.
    const openProjectStep = async (projectName: string) => {
      await app.open('/');
      await expect(grid).toBeVisible();
      await screen.getByTestId('dashboard-add').tap();
      await screen.getByTestId('dashboard-picker-item-3').tap(); // "Karta projektu"
      const picker = screen.getByTestId('dashboard-project-picker');
      await expect(picker).toBeVisible();
      await picker.getByRole('checkbox', 'Tylko aktywne').uncheck(); // new projects start as DRAFT
      await picker.getByRole('textbox', 'Szukaj').fill(projectName);
      return picker;
    };

    try {
      manager = createUser('MANAGER', 'mgr');
      client = createUser('CLIENT', 'client'); // a project requires a manager and a client member
      const members = [
        { userId: manager.id, projectRoleId: await roleIdByCode('MANAGER') },
        { userId: client.id, projectRoleId: await roleIdByCode('CLIENT') },
      ];
      projectIds.push(await createProject(nameA, members), await createProject(nameB, members));
      await clearDashboard(manager);
      await loginAs(app, screen, manager);

      // The picker asks for a project; each chosen project becomes its own tile.
      let picker = await openProjectStep(nameA);
      await picker.getByText(nameA).first().tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();
      await expect(tileOf(nameA)).toBeVisible();

      picker = await openProjectStep(nameB);
      await picker.getByText(nameB).first().tap();
      await expect(screen.getByTestId('dashboard-widget-picker')).toBeHidden();
      await expect(tileOf(nameB)).toBeVisible();

      // The same project again is not added: A still has exactly one tile.
      picker = await openProjectStep(nameA);
      await picker.getByText(nameA).first().tap();
      await screen.getByTestId('dashboard-widget-picker').press('Escape').catch(() => undefined);
      await expect(tileOf(nameA)).toHaveCount(1);
    } finally {
      for (const pid of projectIds) await deleteProject(pid);
      deleteUser(manager); // cascades the user's placements
      deleteUser(client);
    }
  });

});
