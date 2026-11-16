import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { day, get, makeWorld, post, put, shipmentBody, uiLogin } from './support.js';

// FOR-11-04 Part B (UI), BUYER screens: B1 create + list + filters, B2 drawer actions.
// Source: test-cases.md. Environment: tracking is disabled in the Docker stack, so the drawer's
// "Odśwież teraz" is disabled and STUB-driven steps (TC-B1-01 step 4, TC-B2-01, TC-B2-03) are not automated.
// Repeatability: generator (run-id) + teardown (makeWorld().cleanup()).

describe('FOR-11-04 UI: shipments (BUYER)', { tags: ['for-11-04', 'ui'] }, () => {
  /** Steps 1-3 of TC-B1-01 on a fresh world; returns the world for further checks (caller cleans up). */
  const createFromOrder = async (app: any, screen: any, w: Awaited<ReturnType<typeof makeWorld>>) => {
    const p1 = w.position({ quantity: 10 });
    const p2 = w.position({ quantity: 4 });
    const eo = w.order([[p1, 6], [p2, 4]]);
    await uiLogin(app, screen, w.users.BUYER!);

    await app.open(`/deliveries/external-orders/${eo}`);
    await screen.getByTestId('external-order-add-shipment').tap();
    const dialog = screen.getByTestId('shipment-form-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog.getByTestId(`shipment-cap-${p1}`)).toContainText('Wysłano 0 z 10');

    await dialog.getByTestId(`shipment-position-${p1}`).check();
    await dialog.getByTestId(`shipment-quantity-${p1}`).fill('4');
    await dialog.getByTestId(`shipment-position-${p2}`).check();
    await dialog.getByTestId(`shipment-quantity-${p2}`).fill('4');
    await dialog.getByTestId('shipment-carrier').selectOption({ value: 'DPD' });
    await dialog.getByTestId('shipment-tracking-number').fill(`DPD${w.id.toUpperCase()}`);
    await dialog.getByTestId('shipment-submit').tap();
    await expect(dialog).toBeHidden();
    return { p1, p2 };
  };

  test('TC-B1-01 steps 1-3 BUYER creates a shipment from a paid external order (dialog closes, shipment and stages saved)', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const { p1, p2 } = await createFromOrder(app, screen, w);
      const list = await get(`/api/shipments?projectId=${w.projectId}`, w.t('BUYER'));
      expect(list.body.totalElements).toBe(1);
      expect(list.body.content[0].status).toBe('CREATED');
      expect(list.body.content[0].lines).toHaveLength(2);
      expect(w.positionRow(p1).status).toBe('IN_DELIVERY');
      expect(w.positionRow(p2).status).toBe('IN_DELIVERY');
    } finally {
      await w.cleanup();
    }
  });

  // BUG-5 (FINDINGS.md): after saving from the external-order page the user stays there, no redirect to /deliveries/shipments (test-cases.md B1-01 step 3)
  test('TC-B1-01 step 3 after saving the user lands on /deliveries/shipments with the new row "Utworzona"', async ({ app, screen, browser }) => {
    const w = await makeWorld(['BUYER']);
    try {
      await createFromOrder(app, screen, w);
      await expect(browser).toHaveURL(/\/deliveries\/shipments/);
      const list = await get(`/api/shipments?projectId=${w.projectId}`, w.t('BUYER'));
      await expect(screen.getByTestId(`shipment-row-${list.body.content[0].id}`)).toBeVisible();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-01 step 5 a quantity above the limit shows an error at the position row and keeps the dialog open', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const eo = w.order([[p1, 10]]);
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/external-orders/${eo}`);
      await screen.getByTestId('external-order-add-shipment').tap();
      const dialog = screen.getByTestId('shipment-form-dialog');
      await dialog.getByTestId(`shipment-position-${p1}`).check();
      await dialog.getByTestId(`shipment-quantity-${p1}`).fill('11');
      await dialog.getByTestId('shipment-carrier').selectOption({ value: 'DPD' });
      await dialog.getByTestId('shipment-tracking-number').fill(`DPD${w.id.toUpperCase()}`);
      await dialog.getByTestId('shipment-submit').tap();

      await expect(dialog.getByTestId(`shipment-position-row-${p1}`).getByRole('alert')).toContainText('Ilość przekracza');
      await expect(dialog).toBeVisible();
      const list = await get(`/api/shipments?projectId=${w.projectId}`, w.t('BUYER'));
      expect(list.body.totalElements).toBe(0);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-01 client validation: carrier and tracking number are required, nothing is sent', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const eo = w.order([[p1, 10]]);
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/external-orders/${eo}`);
      await screen.getByTestId('external-order-add-shipment').tap();
      const dialog = screen.getByTestId('shipment-form-dialog');
      await dialog.getByTestId(`shipment-position-${p1}`).check();
      await dialog.getByTestId('shipment-submit').tap();

      await expect(dialog.getByTestId('shipment-error-carrierCode')).toHaveText('Pole wymagane');
      await expect(dialog.getByTestId('shipment-error-trackingNumber')).toHaveText('Pole wymagane');
      const list = await get(`/api/shipments?projectId=${w.projectId}`, w.t('BUYER'));
      expect(list.body.totalElements).toBe(0);
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B1-02 status filter lands in the URL, narrows the list and survives a reload', async ({ app, screen, browser }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const buyer = w.t('BUYER');
      const a = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 2]]));
      const b = await post('/api/shipments', buyer, shipmentBody(w, [[p1, 2]]));
      expect(a.status).toBe(201);
      const moved = await put(`/api/shipments/${b.body.id}`, buyer, {
        deliveryMethod: b.body.deliveryMethod, carrierCode: b.body.carrierCode, trackingNumber: b.body.trackingNumber,
        status: 'IN_TRANSIT',
      });
      expect(moved.status, JSON.stringify(moved.body)).toBe(200);
      await uiLogin(app, screen, w.users.BUYER!);

      await app.open(`/deliveries/shipments?project=${w.projectId}`);
      await expect(screen.getByTestId(`shipment-row-${a.body.id}`)).toBeVisible();
      await expect(screen.getByTestId(`shipment-row-${b.body.id}`)).toBeVisible();

      await screen.getByTestId('shipment-filter-status').selectOption({ value: 'IN_TRANSIT' });
      await expect(browser).toHaveURL(/status=IN_TRANSIT/);
      await expect(screen.getByTestId(`shipment-row-${b.body.id}`)).toBeVisible();
      await expect(screen.getByTestId(`shipment-row-${a.body.id}`)).toBeHidden();

      await app.open(`/deliveries/shipments?project=${w.projectId}&status=IN_TRANSIT`); // reload on the same URL
      await expect(screen.getByTestId('shipment-filter-status')).toHaveValue('IN_TRANSIT');
      await expect(screen.getByTestId(`shipment-row-${b.body.id}`)).toBeVisible();
      await expect(screen.getByTestId(`shipment-row-${a.body.id}`)).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-02 manual entry: ETA, window and driver phone are saved and the source reads "Wpisana ręcznie"', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 2]]));
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/shipments/${s.body.id}`);
      await screen.getByTestId('shipment-edit-manual').tap();
      const form = screen.getByTestId('shipment-manual-form');
      await form.getByTestId('manual-eta-date').fill(day(5));
      await form.getByTestId('manual-window-from').fill('09:00');
      await form.getByTestId('manual-window-to').fill('12:00');
      await form.getByTestId('manual-driver-phone').fill('+48 600 100 200');
      await form.getByTestId('manual-save').tap();

      await expect(screen.getByTestId('drawer-eta')).toContainText(day(5));
      await expect(screen.getByTestId('drawer-eta')).toContainText('09:00–12:00');
      await expect(screen.getByTestId('shipment-drawer')).toContainText('Wpisana ręcznie');
      const api = await get(`/api/shipments/${s.body.id}`, w.t('BUYER'));
      expect(api.body.etaSource).toBe('MANUAL');
      expect(api.body.driverPhone).toBe('+48 600 100 200');
      expect(api.body.carrierCode).toBe('DPD'); // the form echoes the method fields, nothing is wiped
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-02 an invalid driver phone is rejected in the manual form', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 2]]));
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/shipments/${s.body.id}`);
      await screen.getByTestId('shipment-edit-manual').tap();
      await screen.getByTestId('manual-driver-phone').fill('abc');
      await screen.getByTestId('manual-save').tap();
      await expect(screen.getByTestId('manual-phone-error')).toBeVisible();
      const api = await get(`/api/shipments/${s.body.id}`, w.t('BUYER'));
      expect(api.body.driverPhone ?? null).toBeNull();
    } finally {
      await w.cleanup();
    }
  });

  // BUG-3 (FINDINGS.md): the manual form offers status "Nieznany" (UNKNOWN) but the API accepts only CREATED/IN_TRANSIT/OUT_FOR_DELIVERY/DELIVERED/EXCEPTION
  test('TC-B2-02 the manual status list offers only statuses the API accepts', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 2]]));
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/shipments/${s.body.id}`);
      await screen.getByTestId('shipment-edit-manual').tap();
      const status = screen.getByTestId('manual-status');
      await expect(status.getByRole('option', 'W drodze')).toHaveCount(1);
      await expect(status.getByRole('option', 'Nieznany')).toHaveCount(0);
    } finally {
      await w.cleanup();
    }
  });

  // BUG-4 (FINDINGS.md): "Wróć do daty przewoźnika" is offered for a manual ETA of a non-carrier shipment where revert is impossible (API 409 revertNotAvailable)
  test('TC-B2-02 step 2 the revert-ETA action is offered only where it can work', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), {
        projectId: w.projectId, deliveryMethod: 'PHONE_CONTACT', driverPhone: '+48 600 100 200', etaDate: day(2),
        lines: [{ positionId: p1, quantity: 1 }],
      });
      expect(s.status).toBe(201);
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/shipments/${s.body.id}`);
      await expect(screen.getByTestId('shipment-drawer')).toContainText('Wpisana ręcznie');
      await expect(screen.getByTestId('shipment-revert-eta')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-01 (partial) "Odśwież teraz" is disabled when no tracker is enabled for the carrier', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 2]]));
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/shipments/${s.body.id}`);
      await expect(screen.getByTestId('shipment-drawer').getByTestId('shipment-refresh')).toBeDisabled();
    } finally {
      await w.cleanup();
    }
  });

  test('TC-B2-04 cancel with confirmation: chip "Anulowana", actions hidden, position back to SUPPLIER_PAID', async ({ app, screen }) => {
    const w = await makeWorld(['BUYER']);
    try {
      const p1 = w.position({ quantity: 10 });
      const s = await post('/api/shipments', w.t('BUYER'), shipmentBody(w, [[p1, 2]]));
      await uiLogin(app, screen, w.users.BUYER!);
      await app.open(`/deliveries/shipments/${s.body.id}`);
      await screen.getByTestId('shipment-cancel').tap();
      await screen.getByTestId('shipment-cancel-confirm-action').tap();

      await expect(screen.getByTestId('drawer-status')).toHaveText('Anulowana');
      await expect(screen.getByTestId('drawer-actions')).toBeHidden();
      expect(w.positionRow(p1).status).toBe('SUPPLIER_PAID');
    } finally {
      await w.cleanup();
    }
  });
});
