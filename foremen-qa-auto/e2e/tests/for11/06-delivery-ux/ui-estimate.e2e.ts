import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { consumptionPick, get, makeWorld, ok, post, psql, seedFinishingBom, seedPhotoMaterial, type World } from './support.js';
import { loginAndOpenTab, pageText } from './ui-support.js';
// FOR-11-06 Part B (UI): B4-01, B4-02, B4-07, B4-08 on a real finishing estimate.
// The finishing BoM is seeded by SQL (REST cannot build it); the project-owned photo material is a copy of a catalog
// material (no catalog material has a photo). Both vanish with the run's project (`makeWorld().cleanup()` in `finally`).
// Repeatability: generator (run-id) + teardown.
/** Seeds a photo material row, a catalog material row and a placeholder; returns the picked data. */
function seedRows(w: World) {
  const pick = consumptionPick();
  const photo = seedPhotoMaterial(w, pick.materialIds[0]!);
  seedFinishingBom(
    w,
    [
      { kind: 'materialRow', materialId: photo.id, roomQty: 10, norm: 1 },
      { kind: 'materialRow', materialId: pick.materialIds[1], roomQty: 10, norm: 1 },
      { kind: 'placeholder', typeId: pick.placeholderTypeId, roomQty: 10, norm: 1 },
    ],
    { workItemId: pick.workItemId },
  );
  return { pick, photo };
}
const syncApi = (w: World) => post(`/api/purchase-positions/sync-from-estimate?projectId=${w.projectId}`, w.t('MANAGER'), undefined);
const idOf = (w: World, where: string): number =>
  Number(psql(`SELECT id FROM purchase_positions WHERE project_id = ${w.projectId} AND estimate_source_key IS NOT NULL AND ${where} ORDER BY id LIMIT 1;`).split('\n')[0]);
describe('FOR-11-06 UI: positions from the estimate', { tags: ['for-11-06', 'ui', 'estimate-sync'] }, () => {
  test('TC-B4-01 "Odśwież z kosztorysu" creates the positions: badge, thumbnail, a row without material has no usable checkbox', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      seedRows(w);
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'positionApprovals');
      await screen.getByTestId('refresh-from-estimate').tap();
      await expect.poll(() => Number(psql(`SELECT COUNT(*) FROM purchase_positions WHERE project_id = ${w.projectId} AND estimate_source_key IS NOT NULL;`))).toBe(3);
      const withPhoto = idOf(w, `planned_material_id IN (SELECT id FROM finishing_materials WHERE project_id = ${w.projectId})`);
      const withoutMaterial = idOf(w, 'planned_material_id IS NULL');
      const photoRow = screen.getByTestId(`position-row-${withPhoto}`);
      await expect(photoRow).toBeVisible();
      await expect(photoRow.getByTestId('estimate-badge-from')).toContainText('Z kosztorysu');
      await expect(photoRow.getByTestId('thumbnail-image')).toBeVisible();
      const bare = screen.getByTestId(`position-row-${withoutMaterial}`);
      await expect(bare.getByTestId('estimate-badge-choose')).toContainText('Wybierz materiał');
      // The row stays in the table with a disabled checkbox (a position without a material cannot be sent).
      await expect(screen.getByTestId(`select-position-${withoutMaterial}`)).toBeDisabled();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-B4-02 "Wybierz materiał" on a row without a material: pick from the project materials, the row gets the model and the thumbnail', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { photo } = seedRows(w);
      expect((await syncApi(w)).status).toBe(200);
      const target = idOf(w, 'planned_material_id IS NULL');
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'positionApprovals');
      const row = screen.getByTestId(`position-row-${target}`);
      await row.getByTestId('choose-material-button').tap();
      const dialog = screen.getByTestId('choose-material-dialog');
      await expect(dialog).toBeVisible();
      await dialog.getByRole('combobox', 'Materiał').tap();
      await screen.getByTestId(`material-source-${photo.id}`).tap();
      await expect(dialog.getByTestId('choose-material-confirm')).toBeEnabled();
      await dialog.getByTestId('choose-material-confirm').tap();
      await expect.poll(() => psql(`SELECT COALESCE(planned_material_id, 0) FROM purchase_positions WHERE id = ${target};`)).toBe(String(photo.id));
      await expect(row).toContainText(photo.model);
      await expect(row.getByTestId('thumbnail-image')).toBeVisible();
      await expect(row.getByTestId('choose-material-button')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-B4-07 CLIENT sees the material picture on the position card and no selection controls or sums', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      seedRows(w);
      expect((await syncApi(w)).status).toBe(200);
      const withPhoto = idOf(w, `planned_material_id IN (SELECT id FROM finishing_materials WHERE project_id = ${w.projectId})`);
      await ok('POST', '/api/position-approvals/staff/send', w.t('MANAGER'), { positionIds: [withPhoto], decisionDeadline: null, managerNote: w.tag('qa06') });
      await loginAndOpenTab(app, screen, browser, w, 'CLIENT', 'positionApprovals');
      const card = screen.getByTestId('client-position-card').first();
      await expect(card).toBeVisible();
      // The picture is a decorative <img alt=""> (no accessible role); the dev CDN host is blocked by the local CSP, so the
      // element is checked in the DOM rather than for a successful load.
      const photoSrc = await browser.evaluate(() => document.querySelector('[data-testid="client-position-card"] img')?.getAttribute('src') ?? '');
      expect(photoSrc).toContain(`qa06/${w.id}.png`);
      await expect(screen.getByTestId(`select-position-${withPhoto}`)).toBeHidden();
      await expect(screen.getByTestId('position-selection-bar')).toBeHidden();
      await expect(screen.getByTestId('selection-totals')).toBeHidden();
      // The client page carries no purchase price block.
      await expect(screen.getByTestId('internal-purchase')).toBeHidden();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-B4-08 the materials matrix tab has no finishing branch: no branch tabs, the finishing material is not listed', async ({ app, screen, browser }) => {
    const w = await makeWorld();
    try {
      const { photo } = seedRows(w);
      expect((await syncApi(w)).status).toBe(200);
      await loginAndOpenTab(app, screen, browser, w, 'MANAGER', 'materialsMatrix');
      await expect(screen.getByRole('heading', 'Materiały: plan / wykonanie')).toBeVisible();
      // With the finishing branch gone the page has no branch tabs at all; the finishing material is not listed.
      const panel: string = await browser.evaluate(() => (document.querySelector('[data-testid="workspace-tab-panel"]') as HTMLElement | null)?.innerText ?? '');
      expect(panel).not.toContain(photo.model);
      expect(panel.toLowerCase()).not.toContain('wykończ');
      expect(await pageText(browser)).not.toContain(photo.model);
      // The API behind the tab agrees.
      const r = await get(`/api/projects/${w.projectId}/materials-matrix`, w.t('MANAGER'));
      expect(r.status, r.text).toBe(200);
      expect(r.text).not.toContain('finishing');
    } finally {
      await w.cleanup();
    }
  });
});
