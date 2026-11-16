import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, plannerRows, setupEnv, teardownEnv, uiLogin, type Dataset, type Env } from './support.js';

// FOR-11-01 Part B: B4 position form (create, work-category link, locked fields / errors).
// Repeatability: run-id dataset created in beforeAll (projects F11-<id>-A/B; positions notes qa-<id>-*) and removed in
// afterAll; the form test writes notes qa-<id>-form so its row is found and removed with the project.
describe('FOR-11-01 UI position form', { tags: ['for-11-01', 'ui'] }, () => {
  let env: Env;
  let ds: Dataset;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });
  const list = () => `/deliveries?project=${env.projectA}&groupBy=NONE&q=qa-${env.id}`;
  const sheet = (screen: any) => screen.getByRole('dialog', 'Nowa pozycja zakupowa');
  const editSheet = (screen: any) => screen.getByRole('dialog', 'Edytuj pozycję');

  // BUG-3 (FINDINGS.md): the "Dodaj pozycję" button is a no-op, PlannerTab gets no onAddPosition so the create sheet never opens
  test('TC-B4-01 a position is created from a catalog material, with quantity, price and note', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId('add-position').tap();
    await expect(sheet(screen)).toBeVisible();
    await sheet(screen).getByRole('combobox', 'Materiał').tap();
    await screen.getByRole('textbox', 'Szukaj materiału').fill('Arbiton Amaron');
    const option = screen.getByRole('option', 'Arbiton Amaron', { exact: false }).first();
    await expect(option).toBeVisible();
    await expect(screen.getByTestId(/^material-source-\d+$/).first()).toHaveText('Katalog');
    await option.tap();
    await sheet(screen).getByLabel('Ilość').fill('3');
    await sheet(screen).getByLabel('Cena sprzedaży (netto)').fill('25');
    await sheet(screen).getByLabel('Uwagi').fill(`qa-${env.id}-form`);
    await sheet(screen).getByRole('button', 'Zapisz').tap();
    await expect(sheet(screen)).toBeHidden({ timeout: 15000 });
    const rows = await plannerRows(env.tok.manager, `projectId=${env.projectA}&q=qa-${env.id}-form`);
    expect(rows.length).toBe(1);
    expect(rows[0].quantity).toBe(3);
    expect(rows[0].money.salePrice).toBe(25);
    expect(rows[0].unitName).toBeTruthy(); // unit taken from the material
  });

  // BUG-3 (FINDINGS.md): create sheet unreachable (add-position button not wired)
  test('TC-B4-02 work category: automatic link, take the date from the schedule, manual override, reset', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId('add-position').tap();
    await expect(sheet(screen)).toBeVisible();
    await sheet(screen).getByRole('combobox', 'Materiał').tap();
    await screen.getByRole('textbox', 'Szukaj materiału').fill('Farba lateksowa Śnieżka Eko Plus biała 10 l');
    await screen.getByRole('option', 'Farba lateksowa', { exact: false }).first().tap();
    const link = sheet(screen).getByTestId('work-category-link');
    await expect(link).toHaveAttribute('data-source', 'AUTO');
    await expect(link).toContainText('Automatycznie');
    await expect(link).toContainText('HYDRAULIKA BIAŁY MONTAŻ');
    await expect(link).toContainText('Start prac:');
    await sheet(screen).getByRole('button', 'Ustaw wg harmonogramu').tap();
    // manual pick from the scheduled categories (C3 only: PRACE MALARSKIE...)
    await link.getByRole('combobox', 'Kategoria prac (powiązanie z harmonogramem)').tap();
    await screen.getByRole('textbox', 'Szukaj kategorii prac w harmonogramie').fill('malarskie');
    await screen.getByRole('option', 'PRACE MALARSKIE', { exact: false }).first().tap();
    await expect(link).toHaveAttribute('data-source', 'MANUAL');
    await expect(link).toContainText('Ręcznie');
    await link.getByRole('button', 'Przywróć automatyczne').tap();
    await expect(link).toHaveAttribute('data-source', 'AUTO');
    await expect(link).toContainText('HYDRAULIKA BIAŁY MONTAŻ');
  });

  // BUG-3 (FINDINGS.md): create sheet unreachable (add-position button not wired)
  test('TC-B4-02 a material without a type has no automatic link and is highlighted', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId('add-position').tap();
    await sheet(screen).getByRole('combobox', 'Materiał').tap();
    await screen.getByRole('textbox', 'Szukaj materiału').fill('PARADYŻ HEARTWOOD CREMA');
    await screen.getByRole('option', 'PARADYŻ HEARTWOOD CREMA', { exact: false }).first().tap();
    const link = sheet(screen).getByTestId('work-category-link');
    await expect(link).toHaveAttribute('data-source', 'NONE');
    await expect(link).toContainText('Nie znaleziono');
    await expect(sheet(screen).getByRole('button', 'Ustaw wg harmonogramu')).toBeDisabled();
  });

  test('TC-B4-03 an ACCEPTED position has locked fields', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId(`row-actions-${ds.P.P3}`, { visible: true }).tap();
    await screen.getByTestId('row-action-EDIT').tap();
    await expect(editSheet(screen)).toBeVisible();
    await expect(screen.getByTestId('locked-hint')).toBeVisible();
    await expect(editSheet(screen).getByRole('combobox', 'Materiał')).toBeDisabled();
  });

  test('TC-B4-03 quantity 0 is rejected with the localized message', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId(`row-actions-${ds.P.P1}`, { visible: true }).tap();
    await screen.getByTestId('row-action-EDIT').tap();
    await expect(editSheet(screen)).toBeVisible();
    await editSheet(screen).getByLabel('Ilość').fill('0');
    await editSheet(screen).getByRole('button', 'Zapisz').tap();
    await expect(editSheet(screen)).toContainText('Ilość musi być większa od zera.');
  });

  // BUG-4 (FINDINGS.md): edit sheet loses projectId / plannedMaterialId on open, so saving fails client-side validation
  test('TC-B4-03 an existing position can be edited and saved', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId(`row-actions-${ds.P.P1}`, { visible: true }).tap();
    await screen.getByTestId('row-action-EDIT').tap();
    await expect(editSheet(screen)).toBeVisible();
    await expect(editSheet(screen).getByRole('combobox', 'Materiał')).toContainText('Arbiton Amaron');
    await editSheet(screen).getByLabel('Uwagi').fill(`typed-${env.id}`);
    await editSheet(screen).getByRole('button', 'Zapisz').tap();
    await expect(editSheet(screen)).toBeHidden({ timeout: 15000 });
    const rows = await plannerRows(env.tok.manager, `projectId=${env.projectA}&q=typed-${env.id}`);
    expect(rows.length).toBe(1);
  });

  // BUG-4 (FINDINGS.md): same root cause, the work-category block of the edit sheet resolves with material 0
  test('TC-B4-02 the edit sheet shows the automatic work category of the position material', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId(`row-actions-${ds.P.P12}`, { visible: true }).tap();
    await screen.getByTestId('row-action-EDIT').tap();
    const link = editSheet(screen).getByTestId('work-category-link');
    await expect(link).toHaveAttribute('data-source', 'AUTO');
    await expect(link).toContainText('HYDRAULIKA BIAŁY MONTAŻ');
  });

  // BUG-4 (FINDINGS.md): edit sheet loses projectId / plannedMaterialId on open, so saving fails client-side validation
  test('TC-B4-03 a server error keeps the sheet open with the typed input', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(list());
    await screen.getByTestId(`row-actions-${ds.P.P5}`, { visible: true }).tap(); // CLIENT_PAID
    await screen.getByTestId('row-action-EDIT').tap();
    await expect(editSheet(screen)).toBeVisible();
    // The server refuses the update (a note-only edit is legal for any status, so the refusal is injected).
    await browser.route(`**/api/purchase-positions/${ds.P.P5}`, async (route: any) => {
      if (route.request.method === 'PUT' || route.request.method === 'PATCH') {
        await route.fulfill({ status: 409, json: { status: 409, code: 'error.purchasePosition.field.readOnly', message: 'Pole jest tylko do odczytu' } });
      } else await route.continue();
    });
    await editSheet(screen).getByLabel('Uwagi').fill(`typed-${env.id}`);
    await editSheet(screen).getByRole('button', 'Zapisz').tap();
    await expect(screen.getByTestId('server-error')).toBeVisible();
    await expect(editSheet(screen).getByLabel('Uwagi')).toHaveValue(`typed-${env.id}`);
  });
});
