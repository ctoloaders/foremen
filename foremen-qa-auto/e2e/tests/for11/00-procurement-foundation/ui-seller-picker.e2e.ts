import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { loginAs } from '../../support/login.js';
import { createWorld, destroyWorld, genNip, http, newPosition, type World } from './support.js';

// FOR-11-00 Part B, B4 (SellerPicker with quick create).
// The "Dodaj pozycję" button of the planner does nothing (BUG-1, FINDINGS.md), so the picker is reached through the
// edit-position sheet ("Akcje" of a planner row), where MaterialPicker embeds SellerPicker + SellerQuickCreate.
// Repeatability: run-id world (sellers named with the run-id are removed by destroyWorld) + teardown.
describe('FOR-11-00 B4 seller picker', { tags: ['for-11-00', 'ui'] }, () => {
  /** Opens the edit sheet of the world's only position as `user` and returns the seller picker trigger. */
  async function openSheet(app: any, screen: any, w: World, user: { email: string; password: string }) {
    await newPosition(w);
    await loginAs(app, screen, user);
    await app.open(`/deliveries?project=${w.projA}`);
    await expect(screen.getByTestId('planner-table')).toBeVisible();
    await screen.getByTestId('group-toggle-NO_REQUEST').first().tap();
    await screen.getByRole('row').filter({ hasText: 'QA-MAT-' }).getByRole('button', 'Akcje').tap();
    await screen.getByTestId('row-action-EDIT').tap();
    const dialog = screen.getByRole('dialog').filter({ has: screen.getByTestId('material-picker') });
    await expect(dialog).toBeVisible();
    return dialog;
  }

  test('TC-B4-01 search finds an existing seller by name part and by NIP and selects it', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const nip = genNip();
      const name = `QA Sprzedawca ${w.id}`;
      expect((await http('POST', '/api/material-sellers', w.admin, { code: `qa_seller_${w.id}`, nameRU: name, namePL: name, nip })).status).toBe(200);
      const dialog = await openSheet(app, screen, w, w.manager);
      const picker = dialog.getByTestId('material-picker').getByRole('combobox', 'Sprzedawca');
      await picker.tap();
      await screen.getByRole('textbox', 'Szukaj po nazwie lub NIP').fill(w.id);
      await expect(screen.getByRole('option').filter({ hasText: name })).toBeVisible();
      await screen.getByRole('textbox', 'Szukaj po nazwie lub NIP').fill(nip);
      await expect(screen.getByRole('option').filter({ hasText: name })).toBeVisible();
      await screen.getByRole('option').filter({ hasText: name }).tap();
      await expect(picker).toContainText(name);
    } finally {
      await destroyWorld(w);
    }
  });


  /** Opens the picker, types an unknown name and opens the quick-create form. */
  async function openQuick(screen: any, dialog: any, typed: string) {
    const picker = dialog.getByTestId('material-picker').getByRole('combobox', 'Sprzedawca');
    await picker.tap();
    await screen.getByRole('textbox', 'Szukaj po nazwie lub NIP').fill(typed);
    await expect(screen.getByText('Brak wyników')).toBeVisible();
    await screen.getByRole('button', '+ Dodaj sprzedawcę').tap();
    const form = screen.getByRole('form', 'Nowy sprzedawca');
    await expect(form).toBeVisible();
    return { picker, form };
  }

  test('TC-B4-02 quick create from the picker without leaving the page', async ({ app, screen, browser }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const dialog = await openSheet(app, screen, w, w.manager);
      const url = new URL(await browser.url()).pathname;
      const { picker, form } = await openQuick(screen, dialog, `Nieznany ${w.id}`);
      await expect(form.getByLabel('Nazwa')).toHaveValue(`Nieznany ${w.id}`);
      await form.getByLabel('Nazwa').fill(`QA Szybki ${w.id}`);
      await form.getByLabel('NIP').fill(genNip());
      await form.getByRole('button', 'Dodaj').tap();
      await expect(screen.getByText('Sprzedawca dodany')).toBeVisible();
      await expect(picker).toContainText(`QA Szybki ${w.id}`);
      expect(new URL(await browser.url()).pathname).toBe(url);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-B4-03 "Szukaj po NIP" in the quick form: existing seller is selected, unknown NIP says "unavailable"', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const nip = genNip();
      const name = `QA Sprzedawca ${w.id}`;
      await http('POST', '/api/material-sellers', w.admin, { code: `qa_seller_${w.id}`, nameRU: name, namePL: name, nip });
      const dialog = await openSheet(app, screen, w, w.manager);
      const { form } = await openQuick(screen, dialog, `Nieznany ${w.id}`);
      await form.getByLabel('NIP').fill(genNip());
      await form.getByRole('button', 'Szukaj po NIP').tap();
      await expect(form.getByText('Wyszukiwanie po NIP jest niedostępne. Uzupełnij dane ręcznie.')).toBeVisible();
      await expect(form.getByLabel('Nazwa')).toBeVisible(); // the form stays available
      await form.getByLabel('NIP').fill(nip);
      await form.getByRole('button', 'Szukaj po NIP').tap();
      await expect(screen.getByText('Sprzedawca z tym NIP już istnieje — wybrano go.')).toBeVisible();
      await expect(dialog.getByTestId('material-picker').getByRole('combobox', 'Sprzedawca')).toContainText(name);
    } finally {
      await destroyWorld(w);
    }
  });

  test('TC-B4-04 a duplicate NIP on create selects the existing seller', async ({ app, screen }) => {
    let w: World | undefined;
    try {
      w = await createWorld();
      const nip = genNip();
      const name = `QA Sprzedawca ${w.id}`;
      await http('POST', '/api/material-sellers', w.admin, { code: `qa_seller_${w.id}`, nameRU: name, namePL: name, nip });
      const dialog = await openSheet(app, screen, w, w.manager);
      const { picker, form } = await openQuick(screen, dialog, `Inna nazwa ${w.id}`);
      await form.getByLabel('NIP').fill(nip);
      await form.getByRole('button', 'Dodaj').tap();
      await expect(screen.getByText('Sprzedawca z tym NIP już istnieje — wybrano go.')).toBeVisible();
      await expect(picker).toContainText(name);
    } finally {
      await destroyWorld(w);
    }
  });
});
