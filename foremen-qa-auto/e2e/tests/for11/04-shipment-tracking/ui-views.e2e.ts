import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { day, eventually, get, makeWorld, notificationsOf, post, psql, put, shipmentBody, uiLogin } from './support.js';

// FOR-11-04 Part B (UI): B3 foreman "upcoming deliveries" and read-only shipments, B4 Gantt overlay, B5 bell.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()).
// Not automated here: PL <-> RU switch of notification titles (TC-B5-01 step 3).

const C1 = 5; // seeded work category ELECTRICAL_ROUGH (read only); makeWorld().schedule() gives the project a Gantt row for it

describe('FOR-11-04 UI: foreman, Gantt overlay, notifications', { tags: ['for-11-04', 'ui'] }, () => {
  test('TC-B3-01 foreman mobile list: bottom bar slot, cards with call / tracking links, periods, deep link, no money', async ({ app, screen, browser }) => {
    const w = await makeWorld(['BUYER', 'FOREMAN']);
    try {
      const p1 = w.position({ quantity: 10 });
      const p2 = w.position({ quantity: 4 });
      const buyer = w.t('BUYER');
      const s1 = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]], { etaDate: day(2), etaTimeFrom: '09:00', etaTimeTo: '12:00', driverPhone: '+48 600 100 200', driverName: 'Jan' }));
      const s2 = await post('/api/shipments', buyer, shipmentBody(w, [[p2, 2]], { etaDate: day(20) }));
      expect(s1.status, JSON.stringify(s1.body)).toBe(201);
      expect(s2.status).toBe(201);

      await browser.setViewport({ width: 360, height: 800 });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open('/');
      // BUG-7 (FINDINGS.md): the mobile navigation drawer is open on first load and covers the bottom bar; close it first
      // BUG-7 is fixed (the drawer starts closed on mobile); close it only if it is open.
      const closeMenu = screen.getByRole('button', 'Close menu', { visible: true });
      if (await closeMenu.isVisible()) await closeMenu.tap();
      // the 4th bottom-bar slot leads to the list
      await screen.getByRole('link', 'Nadchodzące dostawy', { visible: true }).first().tap();
      await expect(browser).toHaveURL(/\/deliveries\/upcoming/);

      const card = screen.getByTestId(`delivery-card-${p1}`);
      await expect(card).toBeVisible();
      await expect(card).toContainText('09:00–12:00');
      await expect(card).toContainText('DPD');
      await expect(card.getByRole('link', 'Zadzwoń do kierowcy')).toHaveAttribute('href', 'tel:+48600100200');
      await expect(card.getByRole('link', 'Otwórz stronę śledzenia')).toBeVisible();
      // 14 days by default: the +20 shipment is outside, the 30-day period brings it in
      await expect(screen.getByTestId(`delivery-card-${p2}`)).toBeHidden();
      await screen.getByRole('button', '30 dni').tap();
      await expect(screen.getByTestId(`delivery-card-${p2}`)).toBeVisible();
      await screen.getByRole('button', '7 dni').tap();
      await expect(screen.getByTestId(`delivery-card-${p2}`)).toBeHidden();
      await expect(card).toBeVisible();
      // no money anywhere on the card
      expect(await card.textContent()).not.toMatch(/zł|PLN|EUR|€/i);

      // notification deep link highlights the card
      await app.open(`/deliveries/upcoming?shipment=${s1.body.id}`);
      await expect(screen.getByTestId(`delivery-card-${p1}`)).toHaveAttribute('data-highlighted', 'true');
    } finally {
      await w.cleanup();
    }
  });

  // BUG-7 (FINDINGS.md): on a 360 px screen the slide-out drawer is open right after sign-in (ui-store sidebarOpen=true) and its backdrop covers the foreman bottom bar
  test('TC-B3-01 step 1 at 360 px the bottom bar is usable right after sign-in (the navigation drawer is closed)', async ({ app, screen, browser }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      await browser.setViewport({ width: 360, height: 800 });
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open('/');
      await expect(screen.getByRole('button', 'Close menu', { visible: true })).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B3-01 empty period shows the empty state', async ({ app, screen }) => {
    const w = await makeWorld(['FOREMAN']);
    try {
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open('/deliveries/upcoming');
      await expect(screen.getByText('Brak dostaw w tym okresie')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B3-02 a foreman of another project does not see these deliveries', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER', 'FOREMAN', 'FOREMAN2']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 4]], { etaDate: day(2) }));
      expect(s.status).toBe(201);
      await uiLogin(app, screen, w.users.FOREMAN2!);
      await app.open('/deliveries/upcoming');
      await expect(screen.getByText('Nadchodzące dostawy').first()).toBeVisible();
      await expect(screen.getByText('Brak dostaw w tym okresie')).toBeVisible();
      await expect(screen.getByTestId(`delivery-card-${p1}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B3-02 a foreman sees /deliveries/shipments read-only: no create / refresh / cancel, note hidden', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER', 'FOREMAN']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 4]], { note: 'internal buyer note', etaDate: day(2) }));
      expect(s.status).toBe(201);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open(`/deliveries/shipments?project=${w.projectId}`);
      await expect(screen.getByTestId(`shipment-row-${s.body.id}`)).toBeVisible();
      await expect(screen.getByTestId('shipment-new')).toBeHidden();
      await expect(screen.getByTestId('shipment-refresh')).toBeHidden();

      await screen.getByTestId(`shipment-open-${s.body.id}`).tap();
      await expect(screen.getByTestId('notice-readonly')).toBeVisible();
      await expect(screen.getByTestId('drawer-actions')).toBeHidden();
      await expect(screen.getByTestId('shipment-cancel')).toBeHidden();
      await expect(screen.getByTestId('drawer-note')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B3-02 a client opening /deliveries/shipments gets the "Brak dostępu" page', async ({ app, screen }) => {
    const w = await makeWorld([]);
    try {
      await uiLogin(app, screen, w.users.CLIENT!);
      await app.open('/deliveries/shipments');
      await expect(screen.getByRole('heading', 'Brak dostępu')).toBeVisible();
      await expect(screen.getByTestId('shipments-page')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-01 the Gantt "Materiały" toggle is off by default, switches on, persists after a reload, switches off', async ({ app, screen }) => {
    const w = await makeWorld([]);
    try {
      w.schedule(C1, 5);
      await uiLogin(app, screen, w.users.MANAGER!);
      await app.open(`/projects/${w.projectId}/scheduleDesign`);
      const toggle = screen.getByTestId('materials-overlay-toggle');
      await expect(toggle).toBeVisible();
      await expect(toggle).toHaveAttribute('aria-pressed', 'false');
      await toggle.tap();
      await expect(toggle).toHaveAttribute('aria-pressed', 'true');

      await app.open(`/projects/${w.projectId}/scheduleDesign`); // reload
      await expect(screen.getByTestId('materials-overlay-toggle')).toHaveAttribute('aria-pressed', 'true');
      await screen.getByTestId('materials-overlay-toggle').tap();
      await expect(screen.getByTestId('materials-overlay-toggle')).toHaveAttribute('aria-pressed', 'false');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-01 manager sees order-by and arrival markers; downtime risk and missed order-by are flagged', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      w.schedule(C1, 5); // works start = today + 4
      const p1 = w.position({ quantity: 10, workCategoryId: C1, orderBy: day(-1) });
      const p4 = w.position({ status: 'WITH_BUYER', quantity: 3, workCategoryId: C1, orderBy: day(-1), needBy: day(6) });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 4]], { etaDate: day(6) }));
      expect(s.status, JSON.stringify(s.body)).toBe(201);
      await uiLogin(app, screen, w.users.MANAGER!);
      await app.open(`/projects/${w.projectId}/scheduleDesign`);
      await screen.getByTestId('materials-overlay-toggle').tap();

      await expect(screen.getByTestId(`materials-overlay-marker-arrival-${p1}`)).toHaveAttribute('data-risk', 'true');
      await expect(screen.getByTestId(`materials-overlay-marker-orderBy-${p4}`)).toHaveAttribute('data-missed', 'true');
      await expect(screen.getByTestId(`materials-overlay-marker-orderBy-${p1}`)).toBeVisible();
      // markers are read-only: plain images, not controls
      await expect(screen.getByTestId(`materials-overlay-marker-arrival-${p1}`)).toHaveAttribute('role', 'img');
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-01 step 4 foreman: read-only Gantt hides order-by markers by default, arrival marker is visible', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER', 'FOREMAN']);
    try {
      w.schedule(C1, 5);
      psql(`UPDATE projects SET status = 'ACTIVE' WHERE id = ${w.projectId};`); // execution tabs need an ACTIVE project
      const p1 = w.position({ quantity: 10, workCategoryId: C1, orderBy: day(-1) });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 4]], { etaDate: day(6) }));
      expect(s.status).toBe(201);
      await uiLogin(app, screen, w.users.FOREMAN!);
      await app.open(`/projects/${w.projectId}/planActual`);
      await screen.getByTestId('materials-overlay-toggle').tap();
      await expect(screen.getByTestId(`materials-overlay-marker-arrival-${p1}`)).toBeVisible();
      await expect(screen.getByTestId(`materials-overlay-marker-orderBy-${p1}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B4-01 step 5 an overlay failure shows a small notice and the Gantt stays usable', async ({ app, screen, browser }) => {
    const w = await makeWorld([]);
    try {
      w.schedule(C1, 5);
      await uiLogin(app, screen, w.users.MANAGER!);
      await browser.route('**/api/purchase-positions/schedule-overlay**', (route: any) =>
        route.fulfill({ status: 500, contentType: 'application/json', json: { message: 'boom' } }),
      );
      await app.open(`/projects/${w.projectId}/scheduleDesign`);
      await screen.getByTestId('materials-overlay-toggle').tap();
      await expect(screen.getByTestId('materials-overlay-error')).toBeVisible();
      await expect(screen.getByTestId('materials-overlay-toggle')).toBeVisible();
      await expect(screen.getByTestId('schedule-tab')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B5-01 bell: shipment notifications with Polish titles (no raw keys), click opens the shipment and marks it read', async ({ app, screen, browser }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const buyer = w.t('BUYER');
      const s = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 4]], { etaDate: day(2) }));
      expect(s.status).toBe(201);
      const later = await put(`/api/shipments/${s.body.id}`, buyer, {
        deliveryMethod: s.body.deliveryMethod, carrierCode: s.body.carrierCode, trackingNumber: s.body.trackingNumber, etaDate: day(5),
      });
      expect(later.status, JSON.stringify(later.body)).toBe(200);
      const shipmentTypes = async () =>
        (await notificationsOf(w.t('MANAGER'))).filter((n) => n.type.startsWith('notification.shipment'));
      const list = await eventually(async () => {
        const l = await shipmentTypes();
        return l.length >= 2 ? l : false;
      });
      expect(list?.map((n) => n.type).sort()).toEqual(['notification.shipment.delayed', 'notification.shipment.shipped']);

      await uiLogin(app, screen, w.users.MANAGER!);
      await app.open('/');
      await screen.getByTestId('notification-bell').tap();
      const shipped = list!.find((n) => n.type === 'notification.shipment.shipped')!;
      const delayed = list!.find((n) => n.type === 'notification.shipment.delayed')!;
      await expect(screen.getByTestId(`notification-type-${shipped.id}`)).toHaveText('Dostawa w drodze');
      await expect(screen.getByTestId(`notification-type-${delayed.id}`)).toHaveText('Dostawa opóźniona');
      expect(await screen.getByTestId('notification-list').textContent()).not.toContain('notification.shipment');

      await screen.getByTestId(`notification-link-${shipped.id}`).tap();
      await expect(browser).toHaveURL(new RegExp(`/deliveries/shipments.*shipment=${s.body.id}`));
      await expect(screen.getByTestId('shipment-drawer')).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  // BUG-6 (FINDINGS.md): clicking a notification only navigates; it is not marked read (NotificationBell.handleNavigate), test-cases.md B5-01 step 2 expects "read"
  test('TC-B5-01 step 2 clicking the notification marks it read', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 4]]));
      expect(s.status).toBe(201);
      const list = await eventually(async () => {
        const l = (await notificationsOf(w.t('MANAGER'))).filter((n) => n.type === 'notification.shipment.shipped');
        return l.length ? l : false;
      });
      await uiLogin(app, screen, w.users.MANAGER!);
      await app.open('/');
      await screen.getByTestId('notification-bell').tap();
      await screen.getByTestId(`notification-link-${list![0]!.id}`).tap();
      await expect(screen.getByTestId('shipment-drawer')).toBeVisible();
      const after = await get('/api/notifications', w.t('MANAGER'));
      expect((after.body as any[]).find((n) => n.id === list![0]!.id).read).toBe(true);
    } finally {
      await w.cleanup();
    }
  });
});
