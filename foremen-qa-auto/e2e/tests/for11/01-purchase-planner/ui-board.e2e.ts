import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, setupEnv, sql, teardownEnv, uiLogin, type Dataset, type Env } from './support.js';

// FOR-11-01 Part B: B6 buyer board.
// Repeatability: run-id dataset created in beforeAll, removed in afterAll; bulk positions carry notes qa-<id>-bulk<n>.
describe('FOR-11-01 UI buyer board', { tags: ['for-11-01', 'ui'] }, () => {
  let env: Env;
  let ds: Dataset;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });
  const board = () => `/deliveries?tab=board&project=${env.projectA}`;
  const card = (screen: any, id: number) => screen.getByTestId(`board-card-${id}`, { visible: true });

  test('TC-B6-01 six columns with counters; own cards first, "all buyers" adds the rest', async ({ app, screen }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(board());
    for (const s of ['WITH_BUYER', 'ORDERED', 'SUPPLIER_PAID', 'IN_DELIVERY', 'PARTIALLY_RECEIVED', 'RECEIVED']) {
      await expect(screen.getByTestId(`board-column-${s}`, { visible: true })).toBeVisible();
    }
    await expect(card(screen, ds.P.P6)).toBeVisible();
    await expect(card(screen, ds.P.P9)).toBeVisible();
    await expect(card(screen, ds.P.P7)).toBeHidden(); // no request, so not "mine"
    await screen.getByRole('combobox', 'Zakupowiec').tap();
    await screen.getByRole('option', 'Wszyscy').tap();
    await expect(card(screen, ds.P.P7)).toBeVisible();
  });

  // BUG-2 (FINDINGS.md): board cards ignore the nested `material` / `money` objects of GET /board (type BoardCard expects flat
  // model/photo/salePrice/...), so the model shows "—" and the price/ceiling block and "Powyżej limitu" never render.
  test('TC-B6-01 card content: model, money block and over-ceiling warning', async ({ app, screen }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(board());
    await expect(card(screen, ds.P.P9)).toContainText('Arbiton Amaron Wood');
    await expect(card(screen, ds.P.P9)).toContainText('Powyżej limitu');
    await expect(card(screen, ds.P.P6)).not.toContainText('Powyżej limitu');
    await expect(card(screen, ds.P.P9).getByTestId('board-card-money')).toBeVisible();
    await expect(card(screen, ds.P.P9)).toContainText(ds.R2ref);
  });

  test('TC-B6-02 hide-delivered hides the RECEIVED cards but keeps the counter', async ({ app, screen }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(board());
    await screen.getByRole('combobox', 'Zakupowiec').tap();
    await screen.getByRole('option', 'Wszyscy').tap();
    await expect(screen.getByTestId('board-hidden-delivered', { visible: true })).toBeVisible(); // default: hidden
    await expect(card(screen, ds.P.P10)).toBeHidden();
    await screen.getByTestId('hide-delivered-switch').uncheck();
    await expect(card(screen, ds.P.P10)).toBeVisible();
  });

  test('TC-B6-02 "show more" loads the next cards of a long column', async ({ app, screen }) => {
    for (let i = 0; i < 31; i++) {
      const r = await api('POST', '/api/purchase-positions', env.tok.manager, {
        projectId: env.projectA, kind: 'FINISHING', sourcingPath: 'PACKAGE', plannedMaterialId: 40, quantity: 1,
        salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-bulk${i}`,
      });
      expect(r.status).toBe(200);
    }
    sql(`UPDATE purchase_positions SET status='WITH_BUYER' WHERE notes LIKE 'qa-${env.id}-bulk%';`);
    await uiLogin(app, screen, env.buyer);
    await app.open(board());
    await screen.getByRole('combobox', 'Zakupowiec').tap();
    await screen.getByRole('option', 'Wszyscy').tap();
    const more = screen.getByTestId('board-load-more-WITH_BUYER', { visible: true });
    await expect(more).toBeVisible();
    const before = await screen.getByTestId(/^board-card-\d+$/, { visible: true }).count();
    await more.tap();
    await expect.poll(async () => screen.getByTestId(/^board-card-\d+$/, { visible: true }).count()).toBeGreaterThan(before);
    await expect(screen.getByTestId('board-count-WITH_BUYER', { visible: true })).toContainText('33');
  });

  test('TC-B6-02 a card click opens the position drawer; there is no drag handle', async ({ app, screen }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(board());
    await card(screen, ds.P.P6).tap();
    await expect(screen.getByTestId('position-drawer')).toBeVisible();
    await expect(screen.getByRole('button', 'drag')).toBeHidden();
  });

  test('TC-B6-03 a cancelled position disappears from the board after a refresh', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(board());
    await expect(card(screen, ds.P.P9)).toBeVisible();
    const r = await api('POST', `/api/purchase-positions/${ds.P.P9}/transition`, env.tok.manager, { target: 'CANCELLED' });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    await browser.reload(); // the focus/60 s auto-refresh is timing dependent and not asserted
    await expect(card(screen, ds.P.P6)).toBeVisible();
    await expect(card(screen, ds.P.P9)).toBeHidden();
  });
});
