import { afterAll, beforeAll, describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { api, buildDataset, CAT, setupEnv, teardownEnv, uiLogin, type Dataset, type Env } from './support.js';

// FOR-11-01 Part B: B5 variants and row actions.
// Repeatability: run-id dataset created in beforeAll (positions notes qa-<id>-*), removed in afterAll;
// action tests use their own fresh positions.
describe('FOR-11-01 UI variants and row actions', { tags: ['for-11-01', 'ui'] }, () => {
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
      salePrice: 10, saleVatRateId: 2, notes: `qa-${env.id}-row${++seq}`, ...over,
    });
    expect(r.status, JSON.stringify(r.body)).toBe(200);
    return r.body.id;
  };
  const flat = (q = '') => `/deliveries?project=${env.projectA}&groupBy=NONE&q=${q || `qa-${env.id}`}`;
  const statusOf = async (id: number) =>
    (await api('GET', `/api/purchase-positions?size=200`, env.tok.admin)).body.content.find((p: any) => p.id === id)?.status;

  test('TC-B5-01 variant history of P8 is shown and a past variant can be restored', async ({ app, screen }) => {
    const variants = await api('GET', `/api/purchase-positions/${ds.P.P8}/variants`, env.tok.manager);
    expect(variants.body.length).toBe(1);
    const vid = variants.body[0].id;
    await uiLogin(app, screen, env.manager);
    await app.open(flat());
    await screen.getByTestId(`variants-toggle-${ds.P.P8}`, { visible: true }).tap();
    await expect(screen.getByTestId(`variant-expander-${ds.P.P8}`)).toBeVisible();
    await expect(screen.getByTestId(`variant-${vid}`)).toContainText('Analog');
    await screen.getByTestId(`variant-restore-${vid}`).tap();
    await expect(screen.getByTestId(`variant-${vid}`)).toBeHidden({ timeout: 15000 });
    const after = await api('GET', `/api/purchase-positions/${ds.P.P8}/variants`, env.tok.manager);
    expect(after.body.length).toBe(1);
    expect(after.body[0].id).not.toBe(vid); // the swapped-out material is the new history entry
  });

  test('TC-B5-01 pick-analog opens the suggestions block', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(flat());
    await screen.getByTestId(`variants-toggle-${ds.P.P1}`, { visible: true }).tap();
    await screen.getByTestId('variant-pick-analog').tap();
    await expect(screen.getByTestId('variant-suggestions')).toBeVisible();
  });

  test('TC-B5-01 an ACCEPTED position has no variant controls', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(flat());
    await expect(screen.getByTestId(`planner-row-${ds.P.P3}`)).toBeVisible();
    await expect(screen.getByTestId(`variants-toggle-${ds.P.P3}`, { visible: true })).toBeHidden();
  });

  test('TC-B5-02 PACKAGE position is passed to the buyer', async ({ app, screen }) => {
    const id = await fresh({ sourcingPath: 'PACKAGE' });
    await uiLogin(app, screen, env.manager);
    await app.open(flat(`qa-${env.id}-row`));
    await screen.getByTestId(`row-actions-${id}`, { visible: true }).tap();
    await screen.getByTestId('row-action-SEND_TO_BUYER').tap();
    await expect.poll(() => statusOf(id), { timeout: 15000 }).toBe('WITH_BUYER');
    await expect(screen.getByTestId(`planner-row-${id}`)).toContainText('U zakupowca');
  });

  test('TC-B5-02 CLIENT_OWN position goes straight to "in delivery"', async ({ app, screen }) => {
    const id = await fresh({ sourcingPath: 'CLIENT_OWN' });
    await uiLogin(app, screen, env.manager);
    await app.open(flat(`qa-${env.id}-row`));
    await screen.getByTestId(`row-actions-${id}`, { visible: true }).tap();
    await screen.getByTestId('row-action-MARK_CLIENT_OWN_IN_DELIVERY').tap();
    await expect.poll(() => statusOf(id), { timeout: 15000 }).toBe('IN_DELIVERY');
    await expect(screen.getByTestId(`planner-row-${id}`)).toContainText('W dostawie');
  });

  test('TC-B5-02 cancel (with confirmation), reopen and delete', async ({ app, screen }) => {
    const id = await fresh();
    await uiLogin(app, screen, env.manager);
    await app.open(flat(`qa-${env.id}-row`));
    await screen.getByTestId(`row-actions-${id}`, { visible: true }).tap();
    await screen.getByTestId('row-action-CANCEL').tap();
    await expect(screen.getByTestId('row-action-confirm')).toBeVisible();
    await screen.getByTestId('row-action-confirm-ok').tap();
    await expect.poll(() => statusOf(id), { timeout: 15000 }).toBe('CANCELLED');
    await expect(screen.getByTestId(`planner-row-${id}`)).toContainText('Anulowane');
    await screen.getByTestId(`row-actions-${id}`, { visible: true }).tap();
    await screen.getByTestId('row-action-REOPEN').tap();
    await expect.poll(() => statusOf(id), { timeout: 15000 }).toBe('PLANNED');
    await screen.getByTestId(`row-actions-${id}`, { visible: true }).tap();
    await screen.getByTestId('row-action-DELETE').tap();
    await screen.getByTestId('row-action-confirm-ok').tap();
    await expect(screen.getByTestId(`planner-row-${id}`)).toBeHidden({ timeout: 15000 });
    expect(await statusOf(id)).toBeUndefined();
  });

  // test-cases.md says "menu without entries" for BUYER, but BUYER holds PURCHASE_POSITIONS UPDATE (FOR-11-00) and
  // requirements R1.7 derives actions from grants: BUYER gets EDIT only (no cancel/delete/transitions).
  test('TC-B5-02 BUYER menu has Edit only, FOREMAN has no menu', async ({ app, screen }) => {
    await uiLogin(app, screen, env.buyer);
    await app.open(`/deliveries?tab=planner&project=${env.projectA}&groupBy=NONE&q=qa-${env.id}`);
    await expect(screen.getByTestId(`planner-row-${ds.P.P1}`)).toBeVisible();
    await screen.getByTestId(`row-actions-${ds.P.P1}`, { visible: true }).tap();
    await expect(screen.getByTestId('row-action-EDIT')).toBeVisible();
    for (const a of ['CANCEL', 'DELETE', 'SEND_TO_BUYER', 'REOPEN']) {
      await expect(screen.getByTestId(`row-action-${a}`)).toBeHidden();
    }
  });

  test('TC-B5-02 FOREMAN has no row action menu', async ({ app, screen }) => {
    await uiLogin(app, screen, env.foreman);
    await app.open(`/deliveries?project=${env.projectA}&groupBy=NONE&q=qa-${env.id}`);
    await expect(screen.getByTestId(`planner-row-${ds.P.P1}`)).toBeVisible();
    await expect(screen.getByTestId(`row-actions-${ds.P.P1}`, { visible: true })).toBeHidden();
  });

  test('TC-B5-02 menu offers only allowed actions', async ({ app, screen }) => {
    await uiLogin(app, screen, env.manager);
    await app.open(flat());
    await screen.getByTestId(`row-actions-${ds.P.P7}`, { visible: true }).tap(); // CLIENT_OWN in delivery
    await expect(screen.getByTestId('row-action-EDIT')).toBeVisible();
    await expect(screen.getByTestId('row-action-SEND_TO_BUYER')).toBeHidden();
    await expect(screen.getByTestId('row-action-DELETE')).toBeHidden();
  });
});
