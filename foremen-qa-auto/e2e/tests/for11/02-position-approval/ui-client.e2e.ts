import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { Env, dayOffset, loginUi, psql } from './support.js';

// FOR-11-02 Part B1-B5 (client screens). Source: FOR-11-02 test-cases.md.
// Repeatability: generator + teardown. Data is prepared through the API (Env); the UI is only driven and checked.
const ST = '/api/position-approvals/staff';
const tabUrl = (env: Env) => `/projects/${env.projectA}/positionApprovals`;

async function openClientTab(env: Env, app: any, screen: any, role = 'client') {
  await loginUi(app, screen, env.users[role]);
  await app.open(tabUrl(env));
  await expect(screen.getByTestId('client-approvals-view')).toBeVisible();
}
const card = (screen: any, text: string) => screen.getByTestId('client-position-card').filter({ hasText: text });
const modelOf = (env: Env, n: number) => `QA-MAT-${env.run}-${n}`;

describe('FOR-11-02 B1 client tab', { tags: ['for-11-02', 'ui'] }, () => {
  test('TC-B1-01 the tab is visible for the client only, in execution only', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      await loginUi(app, screen, env.users.client);
      await app.open(`/projects/${env.projectA}`);
      await expect(screen.getByTestId('workspace-tab-positionApprovals')).toBeVisible();
      await expect(screen.getByTestId('workspace-tab-positionApprovals')).toContainText('Zatwierdzanie pozycji');
      await expect(screen.getByTestId('workspace-tab-procurement')).toBeHidden();
      await expect(screen.getByTestId('workspace-tab-purchases')).toBeHidden();

      // FOREMAN: the tab is absent, other tabs still render
      await loginUi(app, screen, env.users.foreman);
      await app.open(`/projects/${env.projectA}`);
      await expect(screen.getByRole('tab').first()).toBeVisible();
      await expect(screen.getByTestId('workspace-tab-positionApprovals')).toBeHidden();

      // a project in design stage has no such tab for the client either
      psql(`UPDATE projects SET status = 'DRAFT' WHERE id = :pid;`, { pid: String(env.projectA) });
      await loginUi(app, screen, env.users.client);
      await app.open(`/projects/${env.projectA}`);
      await expect(screen.getByRole('tab').first()).toBeVisible();
      await expect(screen.getByTestId('workspace-tab-positionApprovals')).toBeHidden();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B1-02 the tab badge counts positions awaiting a decision', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const p2 = await env.position('2', { sale: 50 });
      await env.send([p1.id, p2.id]);
      await loginUi(app, screen, env.users.client);
      await app.open(`/projects/${env.projectA}`);
      await expect(screen.getByTestId('position-approvals-tab-badge')).toHaveText('2');
      expect((await env.accept(p1.id, 100)).status).toBeLessThan(300);
      await app.open(`/projects/${env.projectA}`);
      await expect(screen.getByTestId('position-approvals-tab-badge')).toHaveText('1');
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 B2 client list', { tags: ['for-11-02', 'ui'] }, () => {
  test('TC-B2-01 grouping by block, overdue badge and filters', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { block: 1 });
      const p3 = await env.position('3', { block: 2, sale: 200 });
      const p6 = await env.position('6', { block: null, sale: 80 });
      await env.send([p1.id, p3.id, p6.id]);
      psql(`UPDATE position_approval_rounds SET decision_deadline = CURRENT_DATE - 1 WHERE id = :rid;`, { rid: String(await env.roundId(p3.id)) });
      await openClientTab(env, app, screen);
      const groups = screen.getByTestId('client-block-group');
      await expect(groups).toHaveCount(3);
      await expect(groups.nth(0)).toContainText('Blok 1');
      await expect(groups.nth(1)).toContainText('Blok 2');
      await expect(groups.nth(2)).toContainText('Bez bloku');
      await expect(groups.nth(0)).toContainText('Decyzja do');
      await expect(groups.nth(1)).toContainText('Termin decyzji minął');

      // filters: accept one position, then "Zaakceptowane" shows exactly it
      expect((await env.accept(p1.id, 100)).status).toBeLessThan(300);
      await app.open(tabUrl(env));
      await screen.getByTestId('filter-accepted').tap();
      await expect(screen.getByTestId('client-position-card')).toHaveCount(1);
      await expect(screen.getByTestId('client-position-card')).toContainText(modelOf(env, 1));
      await screen.getByTestId('filter-all').tap();
      await expect(screen.getByTestId('client-position-card')).toHaveCount(3);
      await screen.getByTestId('filter-pending').tap();
      await expect(screen.getByTestId('client-position-card')).toHaveCount(2);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B2-02 card with prices and no internal data', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, qty: 2, seller: true });
      await env.send([p1.id]);
      await openClientTab(env, app, screen);
      const c = card(screen, modelOf(env, 1));
      await expect(c).toBeVisible();
      await expect(c.getByTestId('client-status-chip')).toContainText('Czeka na Twoją decyzję');
      await expect(c.getByTestId('money-unitNet')).toContainText('100,00');
      await expect(c.getByTestId('money-vat')).toContainText('8');
      await expect(c.getByTestId('money-unitGross')).toContainText('108,00');
      await expect(c.getByTestId('money-lineGross')).toContainText('216,00');
      const view = screen.getByTestId('client-approvals-view');
      for (const secret of ['61,37', '61.37', 'SELLER-SENTINEL', 'Marża', 'Zakup']) {
        await expect(view).not.toContainText(secret);
      }
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 B3 client decisions', { tags: ['for-11-02', 'ui'] }, () => {
  test('TC-B3-01 accepting a position', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, qty: 2 });
      await env.send([p1.id]);
      await openClientTab(env, app, screen);
      await card(screen, modelOf(env, 1)).getByTestId('action-accept').tap();
      const dlg = screen.getByTestId('accept-dialog');
      await expect(dlg).toBeVisible();
      await expect(dlg).toContainText(modelOf(env, 1));
      await expect(dlg).toContainText('108,00');
      await screen.getByTestId('accept-confirm').tap();
      await expect(screen.getByText('Zaakceptowano').first()).toBeVisible({ timeout: 15_000 }); // slow under a full parallel run
      await screen.getByTestId('filter-all').tap();
      const c = card(screen, modelOf(env, 1));
      await expect(c.getByTestId('client-status-chip')).toHaveText('Zaakceptowano');
      await c.getByTestId('history-toggle').tap();
      await expect(c.getByTestId('history-round')).toContainText('Zaakceptowana');
    } finally {
      await env.cleanup();
    }
  });

  // BUG-4 (FINDINGS.md): the 409 priceChanged/stale mapping reads ApiError.code, which the backend never sends, so no
  // "Pozycja została zmieniona" toast appears and the dialog stays open with the outdated price.
  test('TC-B3-02 a changed price is reported and the list refreshes', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p2 = await env.position('2', { sale: 50, purchase: 30 });
      await env.send([p2.id]);
      await openClientTab(env, app, screen);
      await card(screen, modelOf(env, 1)).getByTestId('action-accept').tap();
      await expect(screen.getByTestId('accept-dialog')).toBeVisible();
      const rep = await env.as('manager', 'POST', `/api/purchase-positions/${p2.id}/variants/replace`, { materialId: p2.materialId, salePrice: 55, reason: 'REPRICED' });
      expect(rep.status, rep.text).toBe(200);
      await screen.getByTestId('accept-confirm').tap();
      await expect(screen.getByText('Pozycja została zmieniona. Odśwież widok.').first()).toBeVisible();
      await expect(screen.getByTestId('accept-dialog')).toBeHidden();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B3-03 rejecting needs a reason', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      await openClientTab(env, app, screen);
      await card(screen, modelOf(env, 1)).getByTestId('action-reject').tap();
      await expect(screen.getByTestId('reject-confirm')).toBeDisabled();
      await screen.getByTestId('reject-comment').fill('za drogo');
      await expect(screen.getByTestId('reject-confirm')).toBeEnabled();
      await screen.getByTestId('reject-confirm').tap();
      await expect(screen.getByText('Odrzucono').first()).toBeVisible();
      await screen.getByTestId('filter-all').tap();
      const c = card(screen, modelOf(env, 1));
      await expect(c.getByTestId('client-status-chip')).toHaveText('Przygotowywane');
      await c.getByTestId('history-toggle').tap();
      await expect(c.getByTestId('history-round')).toContainText('za drogo');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B3-04 "I buy it myself"', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, block: 3 });
      await env.send([p6.id]);
      await openClientTab(env, app, screen);
      await card(screen, modelOf(env, 1)).getByTestId('action-client-own').tap();
      await expect(screen.getByTestId('client-own-dialog')).toBeVisible();
      await screen.getByTestId('client-own-confirm').tap();
      await expect(screen.getByText('Zapisano: kupujesz samodzielnie').first()).toBeVisible();
      await screen.getByTestId('filter-clientOwn').tap();
      const c = card(screen, modelOf(env, 1));
      await expect(c.getByTestId('client-status-chip')).toHaveText('Kupujesz samodzielnie');
      await expect(c.getByTestId('money-block')).toHaveCount(0);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B3-05 suggesting an own link', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      await openClientTab(env, app, screen);
      await card(screen, modelOf(env, 1)).getByTestId('action-suggest').tap();
      await screen.getByTestId('suggest-link').fill('abc');
      await expect(screen.getByTestId('suggest-invalid')).toContainText('Podaj poprawny adres http lub https');
      await expect(screen.getByTestId('suggest-confirm')).toBeDisabled();
      await screen.getByTestId('suggest-link').fill('https://shop.example/qa');
      await expect(screen.getByTestId('suggest-invalid')).toHaveCount(0);
      await screen.getByTestId('suggest-confirm').tap();
      await expect(screen.getByText('Propozycja wysłana').first()).toBeVisible();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B3-06 an analog is shown before/after and can be accepted', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p6 = await env.position('6', { sale: 80, block: 3 });
      const m2 = await env.material();
      await env.send([p6.id]);
      const an = await env.as('manager', 'POST', `${ST}/positions/${p6.id}/propose-analog`, { materialId: m2, unitSalePrice: 90, decisionDeadline: dayOffset(5) });
      expect(an.status, an.text).toBe(200);
      await openClientTab(env, app, screen);
      const c = card(screen, modelOf(env, 1));
      await expect(c.getByTestId('option-compare')).toBeVisible();
      await expect(c.getByTestId('option-compare')).toContainText(modelOf(env, 2));
      await expect(c.getByTestId('option-compare')).toContainText('97,20'); // 90 net + 8% VAT
      await c.getByTestId('action-accept').tap();
      await screen.getByTestId('accept-confirm').tap();
      await expect(screen.getByText('Zaakceptowano').first()).toBeVisible({ timeout: 15_000 }); // slow under a full parallel run
      await screen.getByTestId('filter-accepted').tap();
      await expect(screen.getByTestId('client-position-card')).toContainText(modelOf(env, 2));
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B3-07 a package choice shows three options and no prices', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p5 = await env.position('5', { path: 'PACKAGE', sale: null, purchase: null, block: 2 });
      const mats = [await env.material(), await env.material(), await env.material()];
      const r = await env.as('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: mats, decisionDeadline: dayOffset(7) });
      expect(r.status, r.text).toBe(200);
      await openClientTab(env, app, screen);
      await expect(screen.getByTestId('package-choice-list')).toBeVisible();
      await expect(screen.getByTestId('package-choice-list').getByRole('radio')).toHaveCount(3);
      await expect(screen.getByTestId('money-block')).toHaveCount(0);
      await expect(screen.getByTestId('client-approvals-view')).not.toContainText('zł');
    } finally {
      await env.cleanup();
    }
  });

  // BUG-2 (FINDINGS.md): confirming a package choice posts expectedUnitSalePrice=null and the API answers 400 (@NotNull).
  test('TC-B3-07 step 2 choosing the second option fixes the variant', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p5 = await env.position('5', { path: 'PACKAGE', sale: null, purchase: null, block: 2 });
      const mats = [await env.material(), await env.material(), await env.material()];
      await env.as('manager', 'POST', `${ST}/send-choice`, { positionId: p5.id, materialIds: mats, decisionDeadline: dayOffset(7) });
      await openClientTab(env, app, screen);
      await screen.getByTestId('package-choice-list').getByRole('radio').nth(1).check();
      await screen.getByTestId('action-accept').tap();
      await screen.getByTestId('accept-confirm').tap();
      await expect(screen.getByText('Zaakceptowano').first()).toBeVisible({ timeout: 15_000 }); // slow under a full parallel run
      await screen.getByTestId('filter-all').tap();
      await expect(screen.getByTestId('client-status-chip')).toHaveText('Przygotowywane');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B3-08 "discuss in chat" opens the communication tab', async ({ app, screen, browser }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      await openClientTab(env, app, screen);
      await card(screen, modelOf(env, 1)).getByTestId('action-discuss').tap();
      await expect(browser).toHaveURL(/clientCommunication/);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 B4 notifications', { tags: ['for-11-02', 'ui'] }, () => {
  test('TC-B4-01 the bell opens the approval tab and highlights the position', async ({ app, screen, browser }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const p2 = await env.position('2', { sale: 50 });
      const m2 = await env.material();
      await env.send([p1.id, p2.id]);
      await env.as('manager', 'POST', `${ST}/positions/${p2.id}/propose-analog`, { materialId: m2, unitSalePrice: 60, decisionDeadline: dayOffset(5) });
      // wait until both notifications exist
      for (let i = 0; i < 20 && env.notifs('client').filter((n) => n.type.startsWith('notification.position.')).length < 2; i++) {
        await new Promise((r) => setTimeout(r, 400));
      }
      const ids = psql(`SELECT id || ':' || type FROM notifications WHERE recipient_id = :u AND type LIKE 'notification.position.%';`, { u: String(env.users.client.id) })
        .split('\n').map((l) => l.split(':'));
      const analogId = ids.find(([, t]) => t === 'notification.position.analogProposed')![0];
      const requestedId = ids.find(([, t]) => t === 'notification.position.decisionRequested')![0];

      await loginUi(app, screen, env.users.client);
      await app.open('/');
      await screen.getByTestId('notification-bell').tap();
      await expect(screen.getByTestId(`notification-type-${requestedId}`)).toContainText('Nowe pozycje czekają na Twoją decyzję');
      await screen.getByTestId(`notification-link-${requestedId}`).tap();
      await expect(browser).toHaveURL(new RegExp(`/projects/${env.projectA}/positionApprovals`));
      await expect(screen.getByTestId('client-approvals-view')).toBeVisible();

      await screen.getByTestId('notification-bell').tap();
      await expect(screen.getByTestId(`notification-type-${analogId}`)).toContainText('Zaproponowano analog pozycji');
      await screen.getByTestId(`notification-link-${analogId}`).tap();
      await expect(browser).toHaveURL(new RegExp(`positionApprovals\\?position=${p2.id}`));
      await expect(card(screen, modelOf(env, 2))).toBeVisible();
      await expect.poll(() => browser.evaluate((id: number) => {
        const el = document.querySelector(`[data-position-id="${id}"]`);
        return el ? el.className.includes('ring-2') : null;
      }, p2.id)).toBe(true);
    } finally {
      await env.cleanup();
    }
  });
});

describe('FOR-11-02 B5 layout, locales, network', { tags: ['for-11-02', 'ui'] }, () => {
  test('TC-B5-01 360 px: one column, no horizontal scroll, actions reachable', async ({ app, screen, browser }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      const p2 = await env.position('2', { sale: 50 });
      await env.send([p1.id, p2.id]);
      await browser.setViewport({ width: 360, height: 800 });
      await openClientTab(env, app, screen);
      await expect(screen.getByTestId('client-position-card')).toHaveCount(2);
      const m = await browser.evaluate(() => {
        const cards = Array.from(document.querySelectorAll('[data-testid="client-position-card"]')) as HTMLElement[];
        return {
          overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth,
          sameLeft: new Set(cards.map((c) => Math.round(c.getBoundingClientRect().left))).size,
          maxRight: Math.max(...cards.map((c) => c.getBoundingClientRect().right)),
        };
      });
      expect(m.overflow).toBeLessThanOrEqual(0);
      expect(m.sameLeft).toBe(1);
      expect(m.maxRight).toBeLessThanOrEqual(360);
      await expect(card(screen, modelOf(env, 1)).getByTestId('action-accept')).toBeVisible();
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B5-02 RU locale: translated labels, no raw keys', async ({ app, screen, browser }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      psql(`UPDATE users SET locale = 'ru' WHERE id = :u;`, { u: String(env.users.client.id) });
      await loginUi(app, screen, env.users.client, { browser, locale: 'ru' });
      await app.open(tabUrl(env));
      await expect(screen.getByTestId('client-approvals-view')).toBeVisible();
      await expect(screen.getByTestId('workspace-tab-positionApprovals')).toContainText('Согласование позиций');
      const c = card(screen, modelOf(env, 1));
      await expect(c.getByTestId('client-status-chip')).toContainText('Ждёт вашего решения');
      await c.getByTestId('action-accept').tap();
      await expect(screen.getByTestId('accept-dialog')).toBeVisible();
      const text = await screen.getByTestId('client-approvals-view').textContent();
      expect(text).not.toMatch(/positionApproval\./);
      const dlg = await screen.getByTestId('accept-dialog').textContent();
      expect(dlg).not.toMatch(/positionApproval\./);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B5-03 the client screen calls only the allowed API groups', async ({ app, screen, browser }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1');
      await env.send([p1.id]);
      const urls: string[] = [];
      await browser.route('**/api/**', async (route: any) => {
        urls.push(new URL(route.request.url).pathname);
        await route.continue();
      });
      await openClientTab(env, app, screen);
      await card(screen, modelOf(env, 1)).getByTestId('action-accept').tap();
      await screen.getByTestId('accept-confirm').tap();
      await expect(screen.getByText('Zaakceptowano').first()).toBeVisible({ timeout: 15_000 }); // slow under a full parallel run
      await screen.getByTestId('filter-all').tap();
      await expect(screen.getByTestId('client-position-card')).toHaveCount(1);
      const forbidden = urls.filter((u) => /^\/api\/(purchase-|procurement\/|finishing-materials)/.test(u));
      expect(forbidden).toEqual([]);
      expect(urls.some((u) => u.startsWith('/api/position-approvals/client/'))).toBe(true);
      expect(urls.filter((u) => u.startsWith('/api/position-approvals/staff'))).toEqual([]);
    } finally {
      await env.cleanup();
    }
  });
});
