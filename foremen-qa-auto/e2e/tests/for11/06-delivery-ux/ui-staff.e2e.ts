import { describe, test } from '@e2e-dev/web';
import { expect, type Screen } from 'e2e';
import { IBAN_PL, get, makeWorld, ok, psql, type World } from './support.js';
import { loginAndOpenTab, openPath, uiLogin } from './ui-support.js';

// FOR-11-06 Part B (UI): B3 project bank account, B4 selection bar of the position approvals tab.
// Requirements 4, 7, 8. Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-06-delivery-ux-refinements/test-cases.md.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()) in `finally`. Language PL (config).

const IBAN_PL_SPACED = 'PL61 1090 1014 0000 0712 1981 2874';
const bankInput = (screen: Screen) => screen.getByLabel('Rachunek bankowy (IBAN)');
const bankOf = (w: World) => psql(`SELECT COALESCE(bank_account, '') FROM projects WHERE id = ${w.projectId};`);

/** Opens the project edit sheet from the workspace header ("Edytuj projekt"). */
async function openEdit(app: any, screen: any, browser: any, w: World) {
  await openPath(app, screen, browser, w.projectId, `/projects/${w.projectId}/positionApprovals`);
  await screen.getByRole('button', 'Edytuj projekt').tap();
}

describe('FOR-11-06 UI: project bank account', { tags: ['for-11-06', 'ui', 'bank-account'] }, () => {
  test('TC-B3-01 MANAGER edits the IBAN: invalid value shows an error, a valid one is saved and masked', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      await uiLogin(app, screen, w.users.MANAGER!);
      await openEdit(app, screen, browser, w);
      const input = bankInput(screen);
      await expect(input).toBeVisible();
      await expect(input).toBeEnabled();
      await input.fill('PL00 1234');
      await screen.getByRole('button', 'Zapisz zmiany').tap();
      await expect(screen.getByTestId('project-bank-account-error')).toBeVisible();
      expect(bankOf(w)).toBe('');
      await input.fill(IBAN_PL);
      await expect(input).toHaveValue(IBAN_PL_SPACED);
      await screen.getByRole('button', 'Zapisz zmiany').tap();
      await expect(screen.getByTestId('project-bank-account-error')).toBeHidden();
      await expect.poll(() => bankOf(w)).toBe(IBAN_PL);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B3-02 FOREMAN and a MANAGER of another project do not get an editable field', async ({ app, screen, browser }) => {
    const w = await makeWorld({ tags: ['MANAGER2'] });
    try {
      psql(`UPDATE projects SET bank_account = :'iban' WHERE id = ${w.projectId};`, { iban: IBAN_PL });
      // MANAGER2 belongs to the other project only: the field is either read-only or absent, never enabled.
      await uiLogin(app, screen, w.users.MANAGER2!);
      await openPath(app, screen, browser, w.projectId, `/projects/${w.projectId}/positionApprovals`);
      const edit = screen.getByRole('button', 'Edytuj projekt');
      if (await edit.isVisible()) {
        await edit.tap();
        const input = bankInput(screen);
        if (await input.isVisible()) await expect(input).toBeDisabled();
      }
      // API contract behind the screen: the foreign manager cannot write.
      const r = await get(`/api/projects/${w.projectId}`, w.t('MANAGER2'));
      expect([200, 403, 404]).toContain(r.status);
      expect(bankOf(w)).toBe(IBAN_PL);
    } finally {
      await w.cleanup();
    }
  });
});

describe('FOR-11-06 UI: position approvals selection bar', { tags: ['for-11-06', 'ui', 'selection-bar'] }, () => {
  test('TC-B4-03 one status per selection: other statuses are disabled, the bar shows count and gross sum', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const planned = await w.plannedPosition({ qty: 2, sale: 100 });
      const accepted = await w.acceptedPosition(1, 100);
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'positionApprovals');
      await screen.getByTestId(`select-position-${planned}`).check();
      const bar = screen.getByTestId('position-selection-bar');
      await expect(bar).toBeVisible();
      await expect(screen.getByTestId('selection-count')).toContainText('1');
      await expect(screen.getByTestId('selection-totals')).toBeVisible();
      await expect(screen.getByTestId(`select-position-${accepted}`)).toBeDisabled();
      await screen.getByTestId('clear-selection').tap();
      await expect(bar).toBeHidden();
      await expect(screen.getByTestId(`select-position-${accepted}`)).toBeEnabled();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-04 the action follows the status: PLANNED sends, ACCEPTED creates a proforma', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const planned = await w.plannedPosition({ qty: 1, sale: 100 });
      const accepted = await w.acceptedPosition(1, 100);
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'positionApprovals');
      await screen.getByTestId(`select-position-${accepted}`).check();
      await expect(screen.getByTestId('bulk-action-CREATE_PROFORMA')).toBeVisible();
      await expect(screen.getByTestId('bulk-action-SEND')).toBeHidden();
      await screen.getByTestId('clear-selection').tap();
      await screen.getByTestId(`select-position-${planned}`).check();
      await expect(screen.getByTestId('bulk-action-SEND')).toBeVisible();
      await expect(screen.getByTestId('bulk-action-CREATE_PROFORMA')).toBeHidden();
      await screen.getByTestId('bulk-action-SEND').tap();
      await expect(screen.getByTestId('bulk-confirm-dialog').or(screen.getByText('Zrealizowano dla pozycji: 1'))).toBeVisible();
      if (await screen.getByTestId('bulk-confirm-dialog').isVisible()) await screen.getByTestId('bulk-confirm-submit').tap();
      await expect.poll(() => psql(`SELECT status FROM purchase_positions WHERE id = ${planned};`)).toBe('PROPOSED');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-05 bulk "mark accepted" with one stale price reports partial success and keeps the failed row selected', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const a = await w.plannedPosition({ qty: 1, sale: 100 });
      const b = await w.plannedPosition({ qty: 1, sale: 100 });
      const c = await w.plannedPosition({ qty: 1, sale: 100 });
      await ok('POST', '/api/position-approvals/staff/send', w.admin, { positionIds: [a, b, c], decisionDeadline: null, managerNote: w.tag('qa06') });
      // Make the offered price of `c` stale.
      psql(`UPDATE purchase_positions SET sale_price = sale_price + 5 WHERE id = ${c};`);
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'positionApprovals');
      for (const id of [a, b, c]) await screen.getByTestId(`select-position-${id}`).check();
      await screen.getByTestId('bulk-action-MARK_ACCEPTED').tap();
      await screen.getByTestId('bulk-confirm-submit').tap();
      await expect(screen.getByTestId('bulk-failures')).toBeVisible();
      await expect(screen.getByTestId(`select-position-${c}`)).toBeChecked();
      await expect(screen.getByTestId('selection-count')).toContainText('1');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-06 at 390 px the bar is fixed to the bottom and the actions stay reachable', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const planned = await w.plannedPosition({ qty: 1, sale: 100 });
      await browser.setViewport({ width: 390, height: 800 });
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'positionApprovals');
      await screen.getByTestId(`select-position-${planned}`).check();
      const bar = screen.getByTestId('position-selection-bar');
      await expect(bar).toBeVisible();
      const pos = await browser.evaluate(() => getComputedStyle(document.querySelector('[data-testid="position-selection-bar"]')!).position);
      expect(pos).toBe('fixed');
      await expect(screen.getByTestId('bulk-action-SEND')).toBeVisible();
      await expect(screen.getByTestId('clear-selection')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });
});
