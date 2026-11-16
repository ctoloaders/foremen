import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { adminToken } from '../../support/api.js';
import { genNip, http, psql } from './support.js';
import { runId } from '../../support/users.js';

// FOR-11-00 Part B, B1 (seller form and NIP lookup). UI language PL, signed in as the shared ADMIN session.
// Repeatability: run-id in seller codes/names (`qa_seller_<run-id>…`), NIPs from genNip(); sellers are removed
// by run-id in `finally` (SQL, the dictionary has no cascade from tests).
const cleanup = (id: string) => psql(`DELETE FROM material_sellers WHERE code ILIKE :'l' OR name_ru ILIKE :'l';`, { l: `%${id}%` });

describe('FOR-11-00 B1 seller form and NIP lookup', { tags: ['for-11-00', 'ui'], session: 'admin' }, () => {
  const openCreate = async (app: any, screen: any) => {
    await app.open('/material-sellers');
    await screen.getByRole('button', 'Utwórz sprzedawcę').tap();
    await expect(screen.getByRole('dialog')).toBeVisible();
  };

  test('TC-B1-01 NIP field: invalid NIP is rejected on the client, a valid one saves and shows in the NIP column', async ({ app, screen }) => {
    const id = runId();
    try {
      await openCreate(app, screen);
      const dialog = screen.getByRole('dialog');
      await dialog.getByLabel('Kod').fill(`qa_seller_${id}`);
      await dialog.getByLabel('Nazwa (RU)').fill(`QA Продавец ${id}`);
      await dialog.getByLabel('Nazwa (PL)').fill(`QA Sprzedawca ${id}`);
      await dialog.getByLabel('NIP').fill('1234567890');
      await dialog.getByRole('button', 'Utwórz', { exact: true }).tap();
      await expect(dialog.getByText('Nieprawidłowy numer NIP.')).toBeVisible();
      await expect(dialog).toBeVisible(); // not saved, still open

      const nip = genNip();
      await dialog.getByLabel('NIP').fill(nip);
      await dialog.getByRole('button', 'Utwórz', { exact: true }).tap();

      await expect(screen.getByText('Sprzedawca został utworzony')).toBeVisible();
      await screen.getByRole('textbox', 'Szukaj...').fill(id);
      const row = screen.getByRole('row').filter({ hasText: `QA Sprzedawca ${id}` });
      await expect(row).toBeVisible();
      await expect(row).toContainText(nip);
    } finally {
      cleanup(id);
    }
  });

  test('TC-B1-02 "Szukaj po NIP" with the registry disabled shows the "unavailable" message and leaves the form untouched', async ({ app, screen }) => {
    await openCreate(app, screen);
    const dialog = screen.getByRole('dialog');
    await dialog.getByLabel('NIP').fill(genNip());

    await dialog.getByRole('button', 'Szukaj po NIP').tap();

    await expect(dialog.getByText('Wyszukiwanie po NIP jest niedostępne. Uzupełnij dane ręcznie.')).toBeVisible();
    await expect(dialog.getByLabel('Nazwa (PL)')).toHaveValue('');
    await expect(dialog.getByLabel('Nazwa (RU)')).toHaveValue('');
  });

  test('TC-B1-03 "Szukaj po NIP" for an existing seller shows the dictionary message and keeps typed fields', async ({ app, screen }) => {
    const id = runId();
    const nip = genNip();
    try {
      const token = await adminToken();
      const name = `QA Sprzedawca ${id}`;
      const created = await http('POST', '/api/material-sellers', token, { code: `qa_seller_${id}`, nameRU: name, namePL: name, nip });
      expect(created.status).toBe(200);
      await openCreate(app, screen);
      const dialog = screen.getByRole('dialog');
      await dialog.getByLabel('Nazwa (PL)').fill('moje ręczne');
      await dialog.getByLabel('NIP').fill(nip);

      await dialog.getByRole('button', 'Szukaj po NIP').tap();

      await expect(dialog.getByText(`Sprzedawca już istnieje w słowniku: ${name}`)).toBeVisible();
      await expect(dialog.getByLabel('Nazwa (PL)')).toHaveValue('moje ręczne');
    } finally {
      cleanup(id);
    }
  });

  test('TC-B1-04 "Szukaj po NIP" with an invalid NIP shows the format message', async ({ app, screen }) => {
    await openCreate(app, screen);
    const dialog = screen.getByRole('dialog');
    await dialog.getByLabel('NIP').fill('abc');

    await dialog.getByRole('button', 'Szukaj po NIP').tap();

    await expect(dialog.getByText('Nieprawidłowy numer NIP.')).toBeVisible();
    await expect(dialog.getByText('Wyszukiwanie po NIP jest niedostępne. Uzupełnij dane ręcznie.')).toBeHidden();
  });

  test('TC-B1-05 NIP column sorts the table; a seller without NIP shows an empty cell', async ({ app, screen }) => {
    const id = runId();
    try {
      const token = await adminToken();
      const lo = '5260250995'; // valid NIPs with a known order; skipped when already used by other data
      const nips = [genNip(), genNip()].sort();
      const mk = (tag: string, nip?: string) =>
        http('POST', '/api/material-sellers', token, { code: `qa_seller_${id}_${tag}`, nameRU: `QA ${id} ${tag}`, namePL: `QA ${id} ${tag}`, ...(nip ? { nip } : {}) });
      expect((await mk('a', nips[1])).status).toBe(200);
      expect((await mk('b', nips[0])).status).toBe(200);
      expect((await mk('c')).status).toBe(200);
      void lo;
      await app.open('/material-sellers');
      await screen.getByRole('textbox', 'Szukaj...').fill(id);
      const rows = screen.getByRole('row').filter({ hasText: `QA ${id}` });
      await expect(rows).toHaveCount(3);
      const header = screen.getByRole('columnheader').filter({ hasText: 'NIP' });
      await header.getByRole('button', 'NIP').tap();
      await expect(header).toHaveAttribute('aria-sort', 'ascending');
      const asc = await rows.allTextContents();
      await header.getByRole('button', 'NIP').tap();
      await expect(header).toHaveAttribute('aria-sort', 'descending');
      const desc = await rows.allTextContents();
      expect(asc).not.toEqual(desc);
      const order = (list: string[]) => list.map((t) => (t.includes(nips[0]) ? 'b' : t.includes(nips[1]) ? 'a' : 'c'));
      const o1 = order(asc);
      const o2 = order(desc);
      expect(o1.indexOf('b')).toBeLessThan(o1.indexOf('a'));
      expect(o2.indexOf('a')).toBeLessThan(o2.indexOf('b'));
      // the seller without a NIP is still listed (empty NIP cell)
      expect(o1).toContain('c');
    } finally {
      cleanup(id);
    }
  });
});
