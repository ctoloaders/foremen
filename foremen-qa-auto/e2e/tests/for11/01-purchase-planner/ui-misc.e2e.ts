import { readFileSync } from 'node:fs';
import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { buildDataset, setupEnv, sql, teardownEnv, uiLogin, type Dataset, type Env } from './support.js';

// FOR-11-01 Part B: B2-01 columns/markers, B2-03 urgent pick, B2-05 per-user key, B8 locale and mobile.
// Repeatability: run-id dataset created in beforeAll and removed in afterAll. manager2 (RU) is a run-id user whose
// locale is set by SQL and which is deleted with the other users.
describe('FOR-11-01 UI table markers, locale, mobile', { tags: ['for-11-01', 'ui'] }, () => {
  let env: Env;
  let ds: Dataset;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });
  const flat = (extra = '') => `/deliveries?project=${env.projectA}&groupBy=NONE&q=qa-${env.id}${extra}`;
  const row = (screen: any, key: string) => screen.getByTestId(`planner-row-${ds.P[key]}`);

  test('TC-B2-01 table columns, late / delivery-risk markers and sorting', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(flat());
    for (const h of ['Materiał', 'Sprzedawca', 'Ilość', 'Etap', 'Dostawa na budowę', 'Potrzebne do', 'Zamów do', 'Decyzja klienta do', 'Start prac', 'Zlecenie', 'Sprzedaż netto', 'Zakup netto', 'Marża']) {
      await expect(screen.getByRole('columnheader', h, { exact: false }).first()).toBeVisible();
    }
    await expect(row(screen, 'P2').getByTestId('late-badge')).toBeVisible();
    await expect(row(screen, 'P1').getByTestId('late-badge')).toBeHidden();
    await expect(row(screen, 'P12').getByTestId('delivery-risk')).toBeVisible();
    await screen.getByTestId('sort-orderByDate', { visible: true }).tap();
    await expect(screen.getByTestId('planner-table')).toBeVisible();
  });

  test('TC-B2-01 over-ceiling marker is shown to staff on a planner row', async ({ app, screen }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(flat('&tab=planner'));
    await expect(row(screen, 'P9').getByTestId('over-ceiling')).toBeVisible();
    await expect(row(screen, 'P6').getByTestId('over-ceiling')).toBeHidden();
  });

  test('TC-B2-03 "select urgent" ticks all urgent rows of the page', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(flat('&urgent=true'));
    await expect(row(screen, 'P12')).toBeVisible();
    await screen.getByTestId('select-urgent').tap();
    await expect(screen.getByTestId('selection-count')).toHaveText('Zaznaczono: 5'); // P2 P3 P6 P9 P12
  });

  test('TC-B2-05 the hide-delivered key is per user', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(flat());
    await screen.getByTestId('hide-delivered-switch').uncheck();
    const stored = await browser.evaluate((prefix: string) =>
      Object.keys(localStorage).filter((k) => k.startsWith(prefix)).map((k) => [k, localStorage.getItem(k)]), 'foremen:purchase-planner:hide-delivered:');
    expect(stored).toEqual([[`foremen:purchase-planner:hide-delivered:${env.manager.id}`, 'false']]);
  });

  test('TC-B8-01 RU UI: planner and board have no raw i18n keys', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}&groupBy=NONE`);
    await screen.getByRole('button', 'PL').tap(); // the top-bar switch toggles PL -> RU
    await expect(screen.getByRole('tab', 'Планировщик закупок')).toBeVisible();
    await expect(screen.getByTestId('slice-LATE')).toContainText('Просроченные');
    let text = await browser.evaluate(() => document.body.innerText);
    expect(text).not.toMatch(/purchasePlanner\.|buyerKanban\.|pages\.deliveries/);
    await screen.getByRole('tab', 'Доска закупщика').tap();
    await expect(screen.getByTestId('board-tab-body')).toBeVisible();
    await expect(screen.getByTestId('board-column-WITH_BUYER', { visible: true })).toContainText('К заказу');
    text = await browser.evaluate(() => document.body.innerText);
    expect(text).not.toMatch(/purchasePlanner\.|buyerKanban\.|pages\.deliveries/);
  });

  test('TC-B8-01 PL and RU locales cover the same purchasePlanner / buyerKanban keys; pages.deliveries is gone', async () => {
    const dir = '/Users/alexander.borohov/projects/loaders/foremen/foremen-frontend/src/locales';
    const pl = JSON.parse(readFileSync(`${dir}/pl.json`, 'utf8'));
    const ru = JSON.parse(readFileSync(`${dir}/ru.json`, 'utf8'));
    const flatten = (o: any, p = ''): string[] =>
      Object.entries(o).flatMap(([k, v]) => (v && typeof v === 'object' ? flatten(v, `${p}${k}.`) : [`${p}${k}`]));
    for (const ns of ['purchasePlanner', 'buyerKanban']) {
      const a = flatten(pl[ns]).sort();
      const b = flatten(ru[ns]).sort();
      expect(a.filter((k) => !b.includes(k)), `${ns} missing in RU`).toEqual([]);
      expect(b.filter((k) => !a.includes(k)), `${ns} missing in PL`).toEqual([]);
    }
    expect(pl.pages?.deliveries).toBeUndefined();
    expect(ru.pages?.deliveries).toBeUndefined();
  });

  test('TC-B8-02 phone viewport: the table turns into cards and the board shows one column', async ({ app, screen, browser }) => {
    await uiLogin(app, screen, env.buyer);
    await browser.setViewport({ width: 390, height: 844 });
    await app.open(`/deliveries?tab=planner&project=${env.projectA}&groupBy=NONE&q=qa-${env.id}`);
    await expect(screen.getByTestId(`planner-card-${ds.P.P1}`, { visible: true })).toBeVisible();
    await expect(screen.getByRole('table')).toBeHidden();
    await app.open(`/deliveries?tab=board&project=${env.projectA}`);
    await expect(screen.getByRole('combobox', 'Kolumna')).toBeVisible();
    await expect(screen.getByTestId(/^board-column-/, { visible: true })).toHaveCount(1);
  });
});
