import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { addItem, clearDashboard, raw, tokenOf, widgetIdByCode } from '../support/api.js';
import { createUser, deleteUser, type TestUser } from '../support/users.js';

// FOR-10 additional API cases (TC-DX-A*). Source: .kiro/specs/FOR-10-dashboard/additional-test-cases.md.
// API-only (fetch, no browser actions). Repeatability: one fresh ADMIN-role user per test (run-id
// e-mail) with one placement; the user is deleted in `finally`, which cascades its placements.
describe('FOR-10 additional API', { tags: ['for-10', 'api', 'additional'] }, () => {
  const withPlacement = async (body: (token: string, itemId: number, user: TestUser) => Promise<void>) => {
    const user = createUser('ADMIN', 'api');
    try {
      await clearDashboard(user);
      const token = await tokenOf(user);
      const item = await addItem(token, { widgetId: await widgetIdByCode(token, 'PAYMENTS_RECEIVED'), cellIndex: 0 });
      await body(token, item.id, user);
    } finally {
      deleteUser(user);
    }
  };

  test('TC-DX-A01 a malformed date is a 400, not a 500', async () => {
    await withPlacement(async (token, id) => {
      for (const q of ['dateFrom=2026-13-01&dateTo=2026-13-30', 'dateFrom=abc&dateTo=def']) {
        const r = await raw('GET', `/api/dashboard/items/${id}/data?${q}`, { token });
        expect(r.status, q).toBe(400);
      }
    });
  });

  test('TC-DX-A02 a non-numeric placement id is a 400, not a 500', async () => {
    await withPlacement(async (token) => {
      const r = await raw('GET', '/api/dashboard/items/abc/data?dateFrom=2026-11-01&dateTo=2026-11-30', { token });
      expect(r.status).toBe(400);
    });
  });

  test('TC-DX-A03 wrong JSON types and malformed JSON are a 400, not a 500', async () => {
    await withPlacement(async (token, id) => {
      const cases: Array<[string, string, string]> = [
        ['POST', '/api/dashboard/items', '{"widgetId":1,"cellIndex":"abc"}'],
        ['POST', '/api/dashboard/items', '{"widgetId":'],
        ['PUT', `/api/dashboard/items/${id}/move`, '{"cellIndex":"x"}'],
        ['POST', '/api/dashboard/items/swap', '{"firstId":"a","secondId":"b"}'],
      ];
      for (const [m, p, b] of cases) {
        const r = await raw(m, p, { token, body: b });
        expect(r.status, `${m} ${p} ${b}`).toBe(400);
      }
    });
  });

  test('TC-DX-A04 an add without widgetId is a validation error, not "widget not found"', async () => {
    await withPlacement(async (token) => {
      const r = await raw('POST', '/api/dashboard/items', { token, body: '{}' });
      expect(r.status).toBe(400);
      expect(String(r.body?.message)).not.toBe('Nie znaleziono widżetu.');
    });
  });

  test('TC-DX-A05 every dashboard endpoint needs a token', async () => {
    for (const [m, p] of [
      ['GET', '/api/dashboard'],
      ['GET', '/api/dashboard/library'],
      ['GET', '/api/dashboard/appearance'],
      ['POST', '/api/dashboard/items'],
      ['POST', '/api/dashboard/items/swap'],
    ] as const) {
      const r = await raw(m, p, { body: m === 'POST' ? '{}' : undefined });
      expect(r.status, `${m} ${p}`).toBe(401);
    }
  });

  test('TC-DX-A06 the 5-year limit is inclusive: exactly 5 years is 200, one day more is 400', async () => {
    await withPlacement(async (token, id) => {
      const ok = await raw('GET', `/api/dashboard/items/${id}/data?dateFrom=2021-11-14&dateTo=2026-11-14`, { token });
      expect(ok.status).toBe(200);
      const tooLong = await raw('GET', `/api/dashboard/items/${id}/data?dateFrom=2021-11-13&dateTo=2026-11-14`, { token });
      expect(tooLong.status).toBe(400);
    });
  });

  test('TC-DX-A07 a project widget for a non-existent project is the same 403 as a foreign one', async () => {
    await withPlacement(async (token) => {
      const widgetId = await widgetIdByCode(token, 'PROJECT_CARD');
      const r = await raw('POST', '/api/dashboard/items', { token, body: JSON.stringify({ widgetId, projectId: 999_999_999 }) });
      expect(r.status).toBe(403);
    });
  });

  test('TC-DX-A08 a lowercase palette colour is accepted and stored upper-case', async () => {
    await withPlacement(async (token) => {
      const put = await raw('PUT', '/api/dashboard/appearance', { token, body: '{"palette":["#2563eb"]}' });
      expect(put.status).toBe(200);
      const get = await raw('GET', '/api/dashboard/appearance', { token });
      expect(get.body.palette).toEqual(['#2563EB']);
    });
  });

  test('TC-DX-A09 an error message follows Accept-Language (ru / pl)', async () => {
    await withPlacement(async (token, id) => {
      const q = `/api/dashboard/items/${id}/data?dateFrom=2026-12-01&dateTo=2026-11-01`;
      const ru = await raw('GET', q, { token, lang: 'ru' });
      const pl = await raw('GET', q, { token, lang: 'pl' });
      expect(ru.status).toBe(400);
      expect(String(ru.body.message)).toMatch(/[А-Яа-яЁё]/);
      expect(String(pl.body.message)).not.toMatch(/[А-Яа-яЁё]/);
      expect(ru.body.message).not.toBe(pl.body.message);
    });
  });
});
