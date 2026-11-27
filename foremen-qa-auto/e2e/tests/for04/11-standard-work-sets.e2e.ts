import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { adminToken, createRoleWithGrants, deleteRole, raw } from '../support/api.js';
import { loginAs } from '../support/login.js';
import { createUser, deleteUser, runId, type TestUser } from '../support/users.js';
// FOR-04-11 Part C, standard work set matrix (UI). Source:
// .kiro/specs/FOR-04-base-entities/FOR-04-11-work-catalog/test-cases.md, TC-UI-SWM-01..09.
// Repeatability: every test creates its own room type SWS_<RUN>, work category SWS_<RUN> and works
// iw<run>-A/B/C through the API and removes them (pairs first) in `finally`. The dedicated category
// keeps the group counter independent of the seeded catalog. Not automated: RU locale names
// (TC-UI-SWM-04 step 4), empty states (TC-UI-SWM-09) and the sticky column (TC-UI-SWM-03).

interface Fixture {
  run: string;
  roomTypeId: number;
  roomTypeName: string;
  categoryId: number;
  works: Array<{ id: number; name: string }>; // A, B, C
  roomTypeDeleted: boolean;
}

async function api(method: string, path: string, body?: unknown): Promise<any> {
  const r = await raw(method, path, { token: await adminToken(), body: body === undefined ? undefined : JSON.stringify(body) });
  if (r.status >= 300) throw new Error(`${method} ${path} -> ${r.status}: ${JSON.stringify(r.body).slice(0, 300)}`);
  return r.body;
}

async function createFixture(): Promise<Fixture> {
  const run = runId();
  const RUN = run.toUpperCase();
  const f: Fixture = { run, roomTypeId: 0, roomTypeName: `SWS_${RUN}`, categoryId: 0, works: [], roomTypeDeleted: false };
  try {
    f.roomTypeId = (await api('POST', '/api/room-types', { code: `SWS_${RUN}`, nameRU: f.roomTypeName, namePL: f.roomTypeName, active: true })).id;
    f.categoryId = (await api('POST', '/api/work-categories', { code: `SWS_${RUN}`, orderNo: 9000, nameRU: `SWS ${RUN}`, namePL: `SWS ${RUN}`, active: true })).id;
    const unit = (await api('GET', '/api/measurement-units?size=1')).content[0];
    for (const tag of ['A', 'B', 'C']) {
      const name = `iw${run}-${tag}`;
      const w = await api('POST', '/api/work-items', { workCategoryId: f.categoryId, unitId: unit.id, nameRU: name, namePL: name, active: true });
      f.works.push({ id: w.id, name });
    }
  } catch (e) {
    await removeFixture(f);
    throw e;
  }
  return f;
}

/** Removes everything the fixture created; tolerant of already-deleted rows. */
async function removeFixture(f: Fixture): Promise<void> {
  const token = await adminToken();
  const del = (path: string) => raw('DELETE', path, { token });
  if (f.roomTypeId && !f.roomTypeDeleted) {
    const pairs = await pairsOf(f);
    if (pairs.length) {
      await raw('PUT', '/api/standard-work-sets/matrix', {
        token,
        body: JSON.stringify({ add: [], remove: pairs.map((p) => ({ roomTypeId: f.roomTypeId, workItemId: p })) }),
      });
    }
  }
  for (const w of f.works) await del(`/api/work-items/${w.id}`);
  if (f.categoryId) await del(`/api/work-categories/${f.categoryId}`);
  if (f.roomTypeId && !f.roomTypeDeleted) await del(`/api/room-types/${f.roomTypeId}`);
}

/** Work ids in the standard set of the fixture room type (sorted). */
async function pairsOf(f: Fixture): Promise<number[]> {
  const r = await api('GET', `/api/standard-work-sets?query=${encodeURIComponent(`roomType.id==${f.roomTypeId}`)}&size=100`);
  return (r.content as Array<{ workItemId: number }>).map((x) => x.workItemId).sort((a, b) => a - b);
}

async function putMatrix(add: Array<[number, number]>, remove: Array<[number, number]> = []): Promise<void> {
  const pair = ([roomTypeId, workItemId]: [number, number]) => ({ roomTypeId, workItemId });
  await api('PUT', '/api/standard-work-sets/matrix', { add: add.map(pair), remove: remove.map(pair) });
}

const PAGE = '/catalog/standard-work-sets';
const cell = (f: Fixture, i: number) => `sws-cell-${f.works[i].id}-${f.roomTypeId}`;
const addName = (f: Fixture, i: number) => `Dodaj „${f.works[i].name}” do zestawu dla „${f.roomTypeName}”`;
const removeName = (f: Fixture, i: number) => `Usuń „${f.works[i].name}” z zestawu dla „${f.roomTypeName}”`;
const changes = (n: number) => `Zmiany (${n})`;

/** Runs `body` with a fresh fixture and always cleans it up. */
async function withFixture(body: (f: Fixture) => Promise<void>): Promise<void> {
  const f = await createFixture();
  try {
    await body(f);
  } finally {
    await removeFixture(f);
  }
}

type Screen = Parameters<Parameters<typeof test>[1]>[0]['screen'];
type App = Parameters<Parameters<typeof test>[1]>[0]['app'];

async function openMatrix(app: App, screen: Screen, f: Fixture, expand = true): Promise<void> {
  await app.open(PAGE);
  await expect(screen.getByTestId(`sws-room-type-col-${f.roomTypeId}`)).toBeVisible({ timeout: 20000 });
  if (expand) {
    await screen.getByTestId(`sws-group-header-${f.categoryId}`).tap();
    await expect(screen.getByTestId(cell(f, 0))).toBeVisible();
  }
}

describe('FOR-04-11 Standard work set matrix', { tags: ['for-04-11', 'standard-work-sets'], session: 'admin' }, () => {
  test('TC-UI-SWM-01 the page shows the matrix with a column per active room type and groups per category', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await app.open(PAGE);
      await expect(screen.getByRole('heading', 'Standardowe zestawy prac')).toBeVisible();
      await expect(screen.getByTestId(`sws-room-type-col-${f.roomTypeId}`)).toBeVisible({ timeout: 20000 });
      await expect(screen.getByTestId(`sws-group-header-${f.categoryId}`)).toBeVisible();
      // the old add form (selects + "Dodaj" button) is gone
      await expect(screen.getByRole('button', 'Dodaj')).toHaveCount(0);
      await expect(screen.getByTestId('sws-matrix')).toBeVisible();

      // a deactivated room type loses its column
      await api('PUT', `/api/room-types/${f.roomTypeId}`, { code: `SWS_${f.run.toUpperCase()}`, nameRU: f.roomTypeName, namePL: f.roomTypeName, active: false });
      await app.open(PAGE);
      await expect(screen.getByTestId(`sws-group-header-${f.categoryId}`)).toBeVisible({ timeout: 20000 });
      await expect(screen.getByTestId(`sws-room-type-col-${f.roomTypeId}`)).toHaveCount(0);
    });
  });

  test('TC-UI-SWM-02 groups are collapsed by default and show the marked count', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await putMatrix([[f.roomTypeId, f.works[0].id]]);
      await openMatrix(app, screen, f, false);

      await expect(screen.getByTestId(`sws-group-header-${f.categoryId}`)).toHaveAttribute('aria-expanded', 'false');
      await expect(screen.getByTestId(cell(f, 0))).toHaveCount(0);
      // the count is visible while collapsed
      await expect(screen.getByTestId(`sws-group-count-${f.categoryId}`)).toHaveText('W zestawie: 1');

      await screen.getByTestId(`sws-group-header-${f.categoryId}`).tap();
      await expect(screen.getByTestId(`sws-group-header-${f.categoryId}`)).toHaveAttribute('aria-expanded', 'true');
      for (let i = 0; i < 3; i++) await expect(screen.getByTestId(`sws-work-row-${f.works[i].id}`)).toBeVisible();

      await screen.getByTestId(`sws-group-header-${f.categoryId}`).tap();
      await expect(screen.getByTestId(cell(f, 0))).toHaveCount(0);
    });
  });

  test('TC-UI-SWM-03 a work row shows its unit and no NaN or undefined', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await openMatrix(app, screen, f);

      const unit = screen.getByTestId(`sws-work-unit-${f.works[0].id}`);
      await expect(unit).toBeVisible();
      await expect(unit).not.toContainText('NaN');
      await expect(unit).not.toContainText('undefined');
      await expect(screen.getByTestId('sws-unit-col')).toHaveText('Jednostka');
    });
  });

  test('TC-UI-SWM-04 a cell toggles locally, renames itself and saves nothing', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await openMatrix(app, screen, f);
      const c = screen.getByTestId(cell(f, 0));
      await expect(c).toHaveAttribute('data-marked', 'false');
      await expect(screen.getByRole('button', addName(f, 0))).toBeVisible();

      await c.tap();

      await expect(c).toHaveAttribute('data-marked', 'true');
      await expect(c).toHaveAttribute('data-changed', 'true');
      await expect(screen.getByRole('button', removeName(f, 0))).toBeVisible();
      await expect(screen.getByTestId(`sws-group-count-${f.categoryId}`)).toHaveText('W zestawie: 1');
      expect(await pairsOf(f)).toEqual([]); // nothing saved yet

      await c.tap();

      await expect(c).toHaveAttribute('data-marked', 'false');
      await expect(c).toHaveAttribute('data-changed', 'false');
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(0));
    });
  });

  test('TC-UI-SWM-05 the toolbar counts changes and offers undo, redo and discard', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await openMatrix(app, screen, f);
      await expect(screen.getByTestId('sws-toolbar')).toBeVisible();
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(0));
      await expect(screen.getByTestId('sws-save')).toBeDisabled();
      await expect(screen.getByTestId('sws-discard')).toBeDisabled();
      await expect(screen.getByTestId('sws-undo')).toBeDisabled();

      await screen.getByTestId(cell(f, 0)).tap();
      await screen.getByTestId(cell(f, 1)).tap();
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(2));
      await screen.getByTestId('sws-changes-open').tap();
      await expect(screen.getByTestId('sws-changes-list')).toBeVisible();
      await expect(screen.getByTestId('sws-changes-line')).toHaveCount(2);
      await screen.getByTestId('sws-changes-list').press('Escape');

      await screen.getByTestId('sws-undo').tap();
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(1));
      await expect(screen.getByTestId(cell(f, 1))).toHaveAttribute('data-marked', 'false');

      await screen.getByTestId('sws-redo').tap();
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(2));
      await expect(screen.getByTestId(cell(f, 1))).toHaveAttribute('data-marked', 'true');

      await screen.getByTestId('sws-discard').tap();
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(0));
      await expect(screen.getByTestId(cell(f, 0))).toHaveAttribute('data-marked', 'false');
      await expect(screen.getByTestId(cell(f, 1))).toHaveAttribute('data-marked', 'false');
      expect(await pairsOf(f)).toEqual([]);
    });
  });

  test('TC-UI-SWM-06 Save persists the diff, verified through the API and after a reload', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await putMatrix([[f.roomTypeId, f.works[2].id]]); // wC is saved beforehand
      await openMatrix(app, screen, f);
      await screen.getByTestId(cell(f, 0)).tap();
      await screen.getByTestId(cell(f, 1)).tap();
      await screen.getByTestId(cell(f, 2)).tap(); // unmark wC
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(3));

      await screen.getByTestId('sws-save').tap();

      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(0), { timeout: 15000 });
      await expect(screen.getByText('Zestaw zapisany')).toBeVisible();
      expect(await pairsOf(f)).toEqual([f.works[0].id, f.works[1].id].sort((a, b) => a - b));

      await openMatrix(app, screen, f);
      await expect(screen.getByTestId(cell(f, 0))).toHaveAttribute('data-marked', 'true');
      await expect(screen.getByTestId(cell(f, 1))).toHaveAttribute('data-marked', 'true');
      await expect(screen.getByTestId(cell(f, 2))).toHaveAttribute('data-marked', 'false');
    });
  });

  test('TC-UI-SWM-06 step 4: a failed Save keeps the staged changes and stores nothing', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await openMatrix(app, screen, f);
      await screen.getByTestId(cell(f, 0)).tap();
      await screen.getByTestId(cell(f, 1)).tap();
      // the room type disappears behind the screen's back
      await api('DELETE', `/api/room-types/${f.roomTypeId}`);
      f.roomTypeDeleted = true;

      await screen.getByTestId('sws-save').tap();

      // draft stays, Save is usable again for a retry
      await expect(screen.getByTestId('sws-save')).toBeEnabled({ timeout: 15000 });
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(2));
      const stored = await api('GET', `/api/standard-work-sets?size=1000`);
      const ids = new Set(f.works.map((w) => w.id));
      expect((stored.content as Array<{ workItemId: number }>).filter((x) => ids.has(x.workItemId))).toEqual([]);
    });
  });

  test('TC-UI-SWM-07 leaving with unsaved changes asks first; stay keeps them, confirm drops them', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await openMatrix(app, screen, f);
      await screen.getByTestId(cell(f, 0)).tap();

      await screen.getByRole('link', 'Katalog prac').tap();
      await expect(screen.getByTestId('sws-leave-dialog')).toBeVisible();

      await screen.getByTestId('sws-leave-stay').tap();
      await expect(screen.getByTestId('sws-leave-dialog')).toBeHidden();
      await expect(screen.getByTestId('sws-changes-open')).toHaveText(changes(1));

      await screen.getByRole('link', 'Katalog prac').tap();
      await expect(screen.getByTestId('sws-leave-dialog')).toBeVisible();
      await screen.getByTestId('sws-leave-confirm').tap();
      await expect(screen.getByTestId('sws-matrix')).toHaveCount(0, { timeout: 15000 });

      expect(await pairsOf(f)).toEqual([]);
      await openMatrix(app, screen, f);
      await expect(screen.getByTestId(cell(f, 0))).toHaveAttribute('data-marked', 'false');
    });
  });

  test('TC-UI-SWM-07 step 4: leaving without changes needs no confirmation', async ({ app, screen }) => {
    await withFixture(async (f) => {
      await openMatrix(app, screen, f, false);

      await screen.getByRole('link', 'Katalog prac').tap();

      await expect(screen.getByTestId('sws-matrix')).toHaveCount(0, { timeout: 15000 });
      await expect(screen.getByTestId('sws-leave-dialog')).toHaveCount(0);
    });
  });
});

describe('FOR-04-11 Standard work set matrix by role', { tags: ['for-04-11', 'standard-work-sets', 'roles'] }, () => {
  test('TC-UI-SWM-08 a role with READ only sees the matrix read-only', async ({ app, screen }) => {
    const f = await createFixture();
    const role = await createRoleWithGrants(`SWS_${f.run.toUpperCase()}`, [
      ['STANDARD_WORK_SETS', 'READ'],
      ['WORK_CATALOG', 'READ'],
      ['WORK_CATEGORIES', 'READ'],
      ['ROOM_TYPES', 'READ'],
    ]);
    let user: TestUser | undefined;
    try {
      await putMatrix([[f.roomTypeId, f.works[0].id]]);
      user = createUser(role.code, 'swm');
      await loginAs(app, screen, user);
      await openMatrix(app, screen, f);

      await expect(screen.getByTestId(cell(f, 0))).toHaveAttribute('data-marked', 'true');
      await expect(screen.getByTestId(cell(f, 0))).toBeDisabled();
      await expect(screen.getByTestId(cell(f, 1))).toBeDisabled();
      await expect(screen.getByTestId('sws-toolbar')).toHaveCount(0);
      await expect(screen.getByTestId('sws-save')).toHaveCount(0);
    } finally {
      deleteUser(user);
      await deleteRole(role.id);
      await removeFixture(f);
    }
  });

  test('TC-UI-SWM-08 a role without READ is sent to /403', async ({ app, screen, browser }) => {
    const role = await createRoleWithGrants(`SWS_${runId().toUpperCase()}`, []);
    let user: TestUser | undefined;
    try {
      user = createUser(role.code, 'swm0');
      await loginAs(app, screen, user);

      await app.open(PAGE);

      await browser.waitForURL(/\/403/, { timeout: 15000 });
      await expect(screen.getByTestId('sws-matrix')).toHaveCount(0);
    } finally {
      deleteUser(user);
      await deleteRole(role.id);
    }
  });
});
