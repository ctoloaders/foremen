import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, dayShift, setStatus, setupEnv, teardownEnv, uiLogin, type Dataset, type Env } from './support.js';

// FOR-11-01 Part B: B3 selection total, request creation and request actions.
// Repeatability: run-id dataset created in beforeAll (users, projects, positions notes qa-<id>-n, requests QA-<id>-*),
// removed in afterAll; each test that creates data uses fresh positions and a run-id request reference.
describe('FOR-11-01 UI selection and requests', { tags: ['for-11-01', 'ui'] }, () => {
  let env: Env;
  let ds: Dataset;
  let seq = 0;
  beforeAll(async () => {
    env = await setupEnv();
    ds = await buildDataset(env);
  });
  afterAll(async () => {
    await teardownEnv(env);
  });
  const fresh = async (over: Record<string, unknown> = {}): Promise<number> => {
    const r = await api('POST', '/api/purchase-positions', env.tok.manager, {
      projectId: env.projectA, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: 40, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-ui${++seq}`, ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    return r.body.id;
  };
  const listUrl = (extra = '') => `/deliveries?project=${env.projectA}&groupBy=NONE${extra}`;
  const pick = (screen: any, id: number) => screen.getByTestId(`select-row-${id}`);

  test('TC-B3-01 selection panel shows totals, skipped reasons and clears', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(listUrl());
    await pick(screen, ds.P.P3).check();
    await pick(screen, ds.P.P4).check();
    await expect(screen.getByTestId('selection-count')).toHaveText('Zaznaczono: 2');
    await expect(screen.getByTestId('selection-totals')).toContainText('550,00');
    await expect(screen.getByTestId('selection-totals')).toContainText('674,00');
    await pick(screen, ds.P.P1).check();
    await expect(screen.getByTestId('selection-skipped')).toContainText('Pominięte w sumie: 1');
    await expect(screen.getByTestId('selection-skipped')).toContainText('Brak akceptacji klienta');
    await screen.getByTestId('clear-selection').tap();
    // test-cases.md says the panel disappears; the implemented sticky bar stays (it hosts "select urgent") with count 0
    await expect(screen.getByTestId('selection-count')).toHaveText('Zaznaczono: 0');
    await expect(screen.getByTestId('create-request')).toBeDisabled();
    await expect(screen.getByTestId('selection-totals')).toBeHidden();
  });

  test('TC-B3-01 positions of two projects give a warning and the request dialog cannot be saved', async ({ app, screen }) => {
    const accB = await api('POST', '/api/purchase-positions', env.tok.admin, {
      projectId: env.projectB, kind: 'FINISHING', sourcingPath: 'AGREED', plannedMaterialId: 40, quantity: 1,
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-mixB`,
    });
    expect(accB.status).toBe(200);
    setStatus(accB.body.id, 'ACCEPTED');
    await uiLogin(app, screen, { email: 'cto+1@loaders.dev', password: 'alejano123' }); // seeded ADMIN, read-only use
    await app.open(`/deliveries?groupBy=NONE&q=qa-${env.id}-`);
    await pick(screen, ds.P.P3).check();
    await pick(screen, accB.body.id).check();
    await expect(screen.getByTestId('selection-mixed-projects')).toBeVisible();
    await screen.getByTestId('create-request').tap();
    await expect(screen.getByTestId('request-mixed-projects')).toBeVisible();
    await expect(screen.getByTestId('request-submit')).toBeDisabled();
  });

  test('TC-B3-01 BUYER has no create-request button on the planner', async ({ app, screen }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(`/deliveries?tab=planner&groupBy=NONE&project=${env.projectA}`);
    await expect(screen.getByTestId('planner-tab-body')).toBeVisible();
    await expect(screen.getByTestId('create-request')).toBeHidden();
  });

  test('TC-B3-02 a request is created from selected positions with a buyer', async ({ app, screen }) => {
    const a = await fresh({ needByDate: dayShift(40) });
    const b = await fresh({ needByDate: dayShift(20) });
    const ref = `QA-${env.id}-UI`;
    await uiLogin(app, screen, env.manager);
    await app.open(listUrl(`&q=qa-${env.id}-ui`));
    await pick(screen, a).check();
    await pick(screen, b).check();
    await screen.getByTestId('create-request').tap();
    await expect(screen.getByTestId('request-dialog')).toBeVisible();
    await screen.getByTestId('request-reference-number').fill(ref);
    await screen.getByRole('combobox', 'Przypisany zakupowiec').tap();
    await screen.getByRole('option', `QA ${env.buyer.email}`).tap();
    await screen.getByTestId('request-submit').tap();
    await expect(screen.getByTestId('request-dialog')).toBeHidden();
    const list = await api('GET', `/api/purchase-requests?query=referenceNumber==${ref}`, env.tok.admin);
    expect(list.body.content.length).toBe(1);
    expect(list.body.content[0].positionCount).toBe(2);
    expect(list.body.content[0].assignedBuyerId).toBe(env.buyer.id);
    // the buyer sees the notification through the API (bell is covered by TC-B7-01)
    const n = await api('GET', '/api/notifications', env.tok.buyer);
    expect((n.body as any[]).some((x) => x.deepLink === `/deliveries?request=${list.body.content[0].id}`)).toBe(true);
  });

  test('TC-B3-03 request actions: change delivery date, detach a position, delete the empty request', async ({ app, screen }) => {
    const a = await fresh();
    const created = await api('POST', '/api/purchase-requests/from-positions', env.tok.manager, {
      positionIds: [a], referenceNumber: `QA-${env.id}-ACT`, expectedDeliveryDate: dayShift(12),
    });
    expect(created.status).toBe(200);
    const rid = created.body.id;
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}&q=qa-${env.id}-ui`);
    await screen.getByTestId(`group-toggle-${rid}`).first().tap();
    await expect(screen.getByTestId(`planner-row-${a}`)).toBeVisible();
    await screen.getByTestId(`request-detach-${a}`).first().tap();
    await expect(screen.getByTestId(`planner-row-${a}`)).toBeHidden();
    const after = await api('GET', `/api/purchase-requests/${rid}`, env.tok.admin);
    expect(after.status).toBe(200);
    expect(after.body.positionCount ?? after.body.positions?.length ?? 0).toBe(0);
    // the detached position falls into the "no request" group
    const free = await api('GET', `/api/purchase-positions/planner?projectId=${env.projectA}&noRequest=true&q=qa-${env.id}-ui`, env.tok.manager);
    expect(free.body.content.map((r: any) => r.positionId)).toContain(a);
  });

  // BUG-1 (FINDINGS.md): an emptied request disappears from the planner groups, so "Delete empty request" (R17.2) is unreachable
  test('TC-B3-03 an emptied request stays reachable in the planner and can be deleted', async ({ app, screen }) => {
    const a = await fresh();
    const created = await api('POST', '/api/purchase-requests/from-positions', env.tok.manager, {
      positionIds: [a], referenceNumber: `QA-${env.id}-DEL`, expectedDeliveryDate: dayShift(12),
    });
    const rid = created.body.id;
    expect((await api('DELETE', `/api/purchase-requests/${rid}/positions/${a}`, env.tok.manager)).status).toBe(200);
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}`);
    await expect(screen.getByTestId(`request-actions-${rid}`).first()).toBeVisible();
    await screen.getByTestId(`request-actions-${rid}`).first().getByTestId('request-delete').tap();
    await screen.getByTestId('request-delete-confirm').tap();
    await expect(screen.getByTestId('request-delete-dialog')).toBeHidden();
    expect((await api('GET', `/api/purchase-requests/${rid}`, env.tok.admin)).status).toBe(404);
  });

  test('TC-B3-03 editing the request delivery date moves the site delivery date of its positions', async ({ app, screen }) => {
    const a = await fresh();
    const created = await api('POST', '/api/purchase-requests/from-positions', env.tok.manager, {
      positionIds: [a], referenceNumber: `QA-${env.id}-EDT`, expectedDeliveryDate: dayShift(12),
    });
    const rid = created.body.id;
    await uiLogin(app, screen, env.manager);
    await app.open(`/deliveries?project=${env.projectA}&q=qa-${env.id}-ui`);
    await screen.getByTestId(`request-actions-${rid}`).first().getByTestId('request-edit').tap();
    await expect(screen.getByTestId('request-dialog')).toBeVisible();
    await screen.getByTestId('request-notes').fill(`edited-${env.id}`);
    await screen.getByTestId('request-submit').tap();
    await expect(screen.getByTestId('request-dialog')).toBeHidden({ timeout: 20000 });
    const got = await api('GET', `/api/purchase-requests/${rid}`, env.tok.admin);
    expect(got.body.notes).toBe(`edited-${env.id}`);
  });
});
