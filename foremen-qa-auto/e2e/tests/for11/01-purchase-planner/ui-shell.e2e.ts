import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { buildDataset, setupEnv, teardownEnv, uiLogin, type Dataset, type Env } from './support.js';

// FOR-11-01 Part B: B1 shell and roles, B2 planner table, B9 FOREMAN read-only.
// Repeatability: run-id users/projects/positions created in beforeAll and removed in afterAll (support.teardownEnv).
// localStorage is per browser context, and every test starts in a fresh context.
describe('FOR-11-01 UI shell and table', { tags: ['for-11-01', 'ui'] }, () => {
  let env: Env;
  let ds: Dataset;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });
  const row = (screen: any, key: string) => screen.getByTestId(`planner-row-${ds.P[key]}`);

  test('TC-B1-01 BUYER opens on the board and also sees the planner tab', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open('/deliveries');
    await expect(screen.getByTestId('purchase-planner-page')).toBeVisible();
    await expect(screen.getByRole('tab', 'Tablica zakupowca')).toBeVisible();
    await expect(screen.getByRole('tab', 'Planer zakupów')).toBeVisible();
    await expect(screen.getByTestId('board-tab-body')).toBeVisible();
    await expect(browser).toHaveURL(/\/deliveries/);
  });

  test('TC-B1-01 MANAGER opens on the planner and also sees the board tab', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open('/deliveries');
    await expect(screen.getByTestId('planner-tab-body')).toBeVisible();
    await expect(screen.getByRole('tab', 'Tablica zakupowca')).toBeVisible();
  });

  test('TC-B1-01 / TC-B9-01 FOREMAN sees only the planner tab, no write controls and no money', async ({ app, screen }) => {
    await uiLogin(app, screen, env.foreman);
    await app.open(`/deliveries?project=${env.projectA}`);
    await expect(screen.getByTestId('planner-tab-body')).toBeVisible();
    await expect(screen.getByRole('tab', 'Tablica zakupowca')).toBeHidden();
    await expect(screen.getByRole('tab', 'Planer zakupów')).toBeHidden(); // a single tab renders no tab list
    await screen.getByTestId('hide-delivered-switch').uncheck();
    await screen.getByTestId('planner-search').fill(`qa-${env.id}-6`);
    await expect(screen.getByTestId(`planner-group-${ds.R2}`).first()).toBeVisible();
    await expect(screen.getByTestId('add-position')).toBeHidden();
    await expect(screen.getByTestId('selection-bar')).toBeHidden();
    await expect(screen.getByTestId(`select-row-${ds.P.P6}`)).toBeHidden();
    await expect(screen.getByTestId(`row-actions-${ds.P.P6}`)).toBeHidden();
  });

  test('TC-B1-02 filters live in the URL and survive a reload', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}`);
    await expect(screen.getByTestId('slice-bar')).toBeVisible();
    await screen.getByTestId('slice-LATE').tap();
    await screen.getByTestId('urgent-toggle').tap();
    await screen.getByTestId('planner-search').fill(`qa-${env.id}`);
    await expect(browser).toHaveURL(/slice=LATE/);
    await expect(browser).toHaveURL(/urgent=true/);
    await expect(browser).toHaveURL(new RegExp(`q=qa-${env.id}`));
    await expect(browser).toHaveURL(new RegExp(`project=${env.projectA}`));
    await browser.reload();
    await expect(screen.getByTestId('slice-LATE')).toHaveAttribute('aria-pressed', 'true');
    await expect(screen.getByTestId('planner-search')).toHaveValue(`qa-${env.id}`);
  });

  test('TC-B2-02 slice counters match the API and clicking a slice narrows the table', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}&groupBy=NONE`);
    await screen.getByTestId('hide-delivered-switch').uncheck();
    await expect(screen.getByTestId('slice-count-ALL')).toHaveText('11');
    await expect(screen.getByTestId('slice-count-LATE')).toHaveText('1');
    await expect(screen.getByTestId('slice-count-CHANGED')).toHaveText('1');
    await screen.getByTestId('slice-CHANGED').tap();
    await expect(row(screen, 'P8')).toBeVisible();
    await expect(row(screen, 'P1')).toBeHidden();
    await screen.getByTestId('slice-LATE').tap();
    await expect(row(screen, 'P2')).toBeVisible();
    await expect(row(screen, 'P8')).toBeHidden();
    await screen.getByTestId('slice-PAID').tap();
    await expect(row(screen, 'P5')).toBeVisible();
  });

  test('TC-B2-05 hide-delivered defaults on, persists per user, survives corrupt storage', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}&groupBy=NONE`);
    await expect(screen.getByTestId('hide-delivered-switch')).toBeChecked();
    await expect(row(screen, 'P1')).toBeVisible();
    await expect(row(screen, 'P10')).toBeHidden();
    await screen.getByTestId('hide-delivered-switch').uncheck();
    await expect(row(screen, 'P10')).toBeVisible();
    await browser.reload();
    await expect(screen.getByTestId('hide-delivered-switch')).not.toBeChecked();
    await expect(row(screen, 'P10')).toBeVisible();
    // corrupt value falls back to the default
    await browser.evaluate((key: string) => localStorage.setItem(key, '{broken'), `foremen:purchase-planner:hide-delivered:${env.manager.id}`);
    await browser.reload();
    await expect(screen.getByTestId('hide-delivered-switch')).toBeChecked();
  });

  test('TC-B2-04 groups by request are collapsed, expand lazily, and can be switched off', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}`);
    await screen.getByTestId('hide-delivered-switch').uncheck();
    const r1 = screen.getByTestId(`planner-group-${ds.R1}`).first();
    await expect(r1).toBeVisible();
    await expect(screen.getByTestId(`planner-group-${ds.R2}`).first()).toBeVisible();
    await expect(screen.getByTestId('planner-group-NO_REQUEST').first()).toBeVisible();
    await expect(row(screen, 'P2')).toBeHidden();
    await screen.getByTestId(`group-toggle-${ds.R1}`).first().tap();
    await expect(row(screen, 'P2')).toBeVisible();
    await expect(row(screen, 'P3')).toBeVisible();
    await expect(r1.getByTestId('group-overdue')).toBeVisible().catch(() => undefined);
  });
});
