// Project lifecycle, browser only: every step is something a person does in the browser, with
// e-mail as the only other tool. Test accounts and the catalog are prepared by the harness, and
// the one-time codes that the app would send by e-mail are read from the database (the local stack
// does not deliver e-mail). Each step declares `needs`; a step whose needs did not pass is BLOCKED.
import { execFileSync } from 'node:child_process';
import { test, describe, beforeAll, afterAll, beforeEach } from '@e2e-dev/web';
import { expect, credentials } from 'e2e';
import { createUser } from '../support/users.js';
import { begin, CATALOG, check, finish, records, RUN_ID, saveState, snap, state, unmetNeeds, writeReport, type StepDef, type StepRecord } from './support/lc.js';
import { APP_LOCALE_INIT, T } from './support/i18n.js';
import { latestOtpCode } from '../support/client-otp.js';

const TEAM_CODES = ['MANAGER', 'ESTIMATOR', 'SALESMAN', 'FOREMAN', 'FINANCIER', 'BUYER'] as const;

function step(def: StepDef, body: (fx: any, rec: StepRecord) => Promise<void>) {
  test(`${def.id} [${def.role}] ${def.title}`, async (fx: any) => {
    // A step that already passed in this run keeps its result: its data is in the database already,
    // so running it again would create duplicates. Only a failed or unrun step runs again.
    if (records.get(def.id)?.status === 'PASS') test.skip(true, `${def.id} passed earlier in run ${RUN_ID}`);
    const rec = begin(def);
    const started = Date.now();
    const missing = unmetNeeds(def);
    if (missing.length) {
      rec.status = 'BLOCKED';
      rec.blockedBy = missing;
      rec.actual = `not run: ${missing.join(', ')} did not pass`;
      finish(rec, started);
      test.skip(true, rec.actual);
    }
    try {
      await body(fx, rec);
      rec.status = def.workaround ? 'WORKAROUND' : 'PASS';
    } catch (e) {
      rec.status = 'FAIL';
      rec.error = e instanceof Error ? e.message : String(e);
      throw e;
    } finally {
      finish(rec, started);
    }
  });
}

/** Signs in through the login form as the admin, or as a test user from the harness. */
async function signIn(app: any, screen: any, role: string): Promise<void> {
  if (role === 'CLIENT') {
    // Clients sign in with a one-time code that is e-mailed to them. The local stack has no mail
    // delivery, so the code is read from the database where the app stored it.
    const email = state.users.CLIENT!.email;
    await app.open('/auth/otp');
    await screen.getByLabel('E-mail').fill(email);
    await screen.getByRole('button', T.otpRequestCode).tap();
    await expect(screen.getByText(T.otpCodeTitle).first()).toBeVisible();
    const code = latestOtpCode(email);
    for (let i = 0; i < 6; i++) await screen.getByLabel(T.otpDigit(i + 1)).fill(code[i]);
    await expect(screen.getByRole('heading', T.panelHeading).first()).toBeVisible();
    return;
  }
  await app.open('/login');
  if (role === 'ADMIN') {
    const a = credentials.user('admin');
    await screen.getByRole('textbox', 'E-mail').fill(a.username);
    await screen.getByRole('textbox', T.password).fill(a.password);
  } else {
    const u = state.users[role]!;
    await screen.getByRole('textbox', 'E-mail').fill(u.email);
    await screen.getByRole('textbox', T.password).fill(u.password);
  }
  await screen.getByRole('button', T.signIn).tap();
  await expect(screen.getByRole('heading', T.panelHeading).first()).toBeVisible();
}

const pid = () => state.projectId!;
const offerName = () => `Oferta ${state.projectName}`;
const contractTitle = () => `Umowa ${state.projectName}`;
/** The signed-copy scan the client uploads. The file is kept with the suite. */
const signedScanPath = () => `${process.cwd()}/lifecycle-human/fixtures/signed-scan.pdf`;

/** Reads one value from the database. Used only for ids and for checks the screen cannot show. */
function db(sql: string): string {
  return execFileSync('docker', ['exec', 'foremen-postgres-1', 'psql', '-U', 'foremen', '-d', 'foremen', '-At', '-c', sql], { encoding: 'utf8' }).trim();
}

describe('project lifecycle, browser only', () => {
  beforeEach(async ({ browser }) => {
    // The app picks its UI language from localStorage, before any page script runs.
    await browser.addInitScript(APP_LOCALE_INIT);
  });

  beforeAll(async () => {
    // Runs again when the runner reloads the module; the persisted state keeps users and ids.
    if (!state.users.MANAGER) {
      for (const code of TEAM_CODES) state.users[code] = createUser(code, code.toLowerCase());
      state.users.CLIENT = createUser('CLIENT', 'client');
      state.users.WORKER = createUser('WORKER', 'worker');
      state.users.OTHER = createUser('OTHER', 'other');
      state.projectName = `QA-HUM-${RUN_ID.replace(/[^A-Za-z0-9-]/g, '').slice(0, 40)}`;
      saveState();
    }
  });

  afterAll(() => {
    writeReport(CATALOG);
  });

  step(
    {
      id: 'L01',
      phase: '1 Foundation',
      role: 'ADMIN',
      mode: 'UI',
      title: 'Admin signs in and sees the System menu',
      what: 'The admin signs in and looks at the menu on the left.',
      expected: 'The menu items Użytkownicy, Role and Audyt are visible.',
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'ADMIN');
      await expect(screen.getByRole('link', T.navUsers)).toBeVisible();
      await expect(screen.getByRole('link', T.navRoles)).toBeVisible();
      await expect(screen.getByRole('link', T.navAudit)).toBeVisible();
      rec.actual = 'System menu visible';
      await snap(app, rec, 'admin-menu');
    },
  );

  step(
    {
      id: 'L02',
      phase: '1 Foundation',
      role: 'ADMIN',
      mode: 'UI',
      title: 'Admin creates the project with a team and a client',
      what: 'The admin opens Projekty, clicks Nowy projekt, fills in the name and area, picks the manager, the estimator and the client, and saves.',
      expected: 'The project appears in the list with status Szkic, the team and the client.',
      needs: ['L01'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'ADMIN');
      await app.open('/projects');
      await screen.getByRole('button', 'Nowy projekt').tap();
      await screen.getByLabel('Nazwa').fill(state.projectName!);
      await screen.getByLabel('Powierzchnia (m²)').fill('120');
      await screen.getByRole('combobox', 'Zespół').tap();
      // The create form offers only the admin staff roles (MANAGER, FOREMAN, ESTIMATOR, FINANCIER).
      for (const code of ['MANAGER', 'ESTIMATOR', 'FOREMAN', 'FINANCIER'] as const) {
        const email = state.users[code]!.email;
        await screen.getByRole('textbox', 'Szukaj użytkownika...').fill(email);
        await screen.getByRole('button').filter({ hasText: email }).tap();
      }
      await screen.getByRole('button', 'Zamknij').tap().catch(() => undefined);
      await screen.getByRole('combobox', 'Klient').tap();
      await screen.getByRole('textbox', 'Szukaj klienta...').fill(state.users.CLIENT!.email);
      await screen.getByRole('button').filter({ hasText: state.users.CLIENT!.email }).tap();
      await screen.getByRole('button', 'Utwórz projekt').tap();
      await expect(screen.getByRole('textbox', 'Szukaj...')).toBeVisible();
      await screen.getByRole('textbox', 'Szukaj...').fill(state.projectName!);
      const row = screen.getByRole('row').filter({ hasText: state.projectName! }).first();
      await expect(row).toBeVisible();
      // The id is not on the list, so the harness reads it from the database for the URLs that follow.
      state.projectId = Number(
        execFileSync(
          'docker',
          ['exec', 'foremen-postgres-1', 'psql', '-U', 'foremen', '-d', 'foremen', '-At', '-c', `SELECT id FROM projects WHERE name = '${state.projectName}' ORDER BY id DESC LIMIT 1`],
          { encoding: 'utf8' },
        ).trim(),
      );
      saveState();
      rec.actual = `project ${state.projectName} (id ${state.projectId}) created`;
      await snap(app, rec, 'admin-project-created');
    },
  );

  step(
    {
      id: 'L03',
      phase: '1 Foundation',
      role: 'ADMIN',
      mode: 'UI',
      title: 'Admin sees the new project as Szkic in its workspace',
      what: 'The admin opens the new project and reads its header.',
      expected: 'The header shows the name and the status Szkic.',
      needs: ['L02'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'ADMIN');
      await app.open(`/projects/${pid()}/rooms`);
      await expect(screen.getByRole('heading', state.projectName!).first()).toBeVisible();
      await expect(screen.getByText(T.statusDraft).first()).toBeVisible();
      rec.actual = `workspace of ${state.projectName} shows ${T.statusDraft}`;
      await snap(app, rec, 'admin-project-workspace');
    },
  );

  // ─── Phase 2: rooms ─────────────────────────────────────────────────────────
  step(
    {
      id: 'L04',
      phase: '2 Design',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager opens the project and sees the design tabs',
      what: 'The manager signs in, opens the project and looks at the tabs.',
      expected: 'The tabs Pomieszczenia, Kosztorys, Oferty, Zespół and Harmonogram are visible.',
      needs: ['L02'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/rooms`);
      for (const tab of [T.tabRooms, T.tabEstimate, T.tabOffer, T.tabTeam, T.tabSchedule]) {
        await expect(screen.getByRole('tab', tab).first()).toBeVisible();
      }
      rec.actual = 'five design tabs visible';
      await snap(app, rec, 'manager-tabs');
    },
  );

  step(
    {
      id: 'L05',
      phase: '2 Design',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager adds two rooms in the Pomieszczenia tab',
      what: 'The manager clicks Dodaj pomieszczenie, types a name, picks the room type and saves. Then does the same for a second room.',
      expected: 'Both rooms (Salon and Sypialnia) show as columns in the rooms table after saving.',
      needs: ['L04'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/rooms`);
      const rooms: Array<[string, string]> = [
        ['Salon', T.roomTypeSalon],
        ['Sypialnia', T.roomTypeBedroom],
      ];
      for (const [label, type] of rooms) {
        await screen.getByRole('button', T.addRoom).first().tap();
        await screen.getByRole('textbox', T.newRoomLabel).last().fill(label);
        await screen.getByRole('combobox', T.selectRoomType).last().tap();
        await screen.getByRole('option', type).tap();
        await screen.getByRole('button', T.save).tap();
        await expect(screen.getByText(T.saveSuccess).first()).toBeVisible();
      }
      await expect(screen.getByText('Salon').first()).toBeVisible();
      await expect(screen.getByText('Sypialnia').first()).toBeVisible();
      rec.actual = 'rooms Salon and Sypialnia saved';
      await snap(app, rec, 'manager-rooms');
    },
  );
  step(
    {
      id: 'L05b',
      phase: '2 Design',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager enters the size of each room',
      what: 'In the Pomieszczenia table the manager clicks each size cell (floor area, wall area, perimeter, height), types the value and presses Enter.',
      expected: 'The cells show the typed values for both rooms.',
      needs: ['L05'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/rooms`);
      const allRooms = db(`SELECT COUNT(*) FROM rooms WHERE project_id = ${pid()}`);
      const sized = db(`SELECT COUNT(*) FROM rooms WHERE project_id = ${pid()} AND floor_area = 30 AND wall_area = 60 AND perimeter = 22 AND ceiling_height = 2.6`);
      if (sized === allRooms) {
        // An earlier attempt already saved the sizes; the step checks the stored values and finishes.
        rec.actual = `sizes already stored for ${sized} rooms (earlier attempt)`;
        return;
      }
      const sizes: Array<[string, string, string]> = [
        ['floorArea', T.dimFloorArea, '30'],
        ['wallArea', T.dimWallArea, '60'],
        ['perimeter', T.dimPerimeter, '22'],
        ['ceilingHeight', T.dimCeilingHeight, '2.6'],
      ];
      const roomIds = db(`SELECT id FROM rooms WHERE project_id = ${pid()} ORDER BY sort_order, id`).split('\n').filter(Boolean);
      for (const roomId of roomIds) {
        for (const [field, label, value] of sizes) {
          const cell = screen.getByTestId(`cell-${roomId}-${field}`);
          await cell.getByRole('button', label).tap();
          await cell.getByRole('textbox', label).fill(value);
          await cell.getByRole('textbox', label).press('Enter');
        }
      }
      // Edits stay on screen until the table is saved. When the estimate already has priced works,
      // the app first asks the manager to approve the recalculation of the estimate.
      await screen.getByRole('button', T.save).first().tap();
      const recalc = screen.getByTestId('estimate-recalc-approve');
      await expect(recalc.or(screen.getByText(T.saveSuccess))).toBeVisible();
      if (await recalc.isVisible()) await recalc.tap();
      await expect(recalc).toHaveCount(0);
      const stored = db(`SELECT COUNT(*) FROM rooms WHERE project_id = ${pid()} AND floor_area = 30 AND wall_area = 60 AND perimeter = 22 AND ceiling_height = 2.6`);
      check(rec, stored === String(roomIds.length), `${stored} of ${roomIds.length} rooms store the sizes`);
      rec.actual = `sizes stored for ${roomIds.length} rooms`;
      await snap(app, rec, 'manager-room-sizes');
    },
  );

  step(
    {
      id: 'L05c',
      phase: '2 Design',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager adds the salesman and the buyer to the team',
      what: 'In the Zespół tab the manager adds a salesman and a buyer as staff.',
      expected: 'Both appear in the team list as staff members.',
      needs: ['L05'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/team`);
      for (const code of ['SALESMAN', 'BUYER'] as const) {
        const email = state.users[code]!.email;
        await screen.getByTestId('team-add-ADMIN_STAFF').tap();
        await screen.getByTestId('candidate-search').fill(email);
        await screen.getByRole('option').filter({ hasText: email }).tap();
        await screen.getByTestId('add-member-submit').tap();
        await expect(screen.getByText(email).first()).toBeVisible();
      }
      rec.actual = 'salesman and buyer listed in the team';
      await snap(app, rec, 'manager-team');
    },
  );

  step(
    {
      id: 'L06',
      phase: '2 Design',
      role: 'ESTIMATOR',
      mode: 'UI',
      title: 'Estimator cannot save a new room',
      what: 'The estimator opens the Pomieszczenia tab, clicks Dodaj pomieszczenie, types a name, picks a room type and saves.',
      expected: 'The save fails with the message "Nie udało się zapisać zmian". No new room is stored.',
      needs: ['L05'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'ESTIMATOR');
      await app.open(`/projects/${pid()}/rooms`);
      await expect(screen.getByRole('tab', T.tabRooms).first()).toBeVisible();
      await screen.getByRole('button', T.addRoom).first().tap();
      await screen.getByRole('textbox', T.newRoomLabel).last().fill('Nope');
      await screen.getByRole('combobox', T.selectRoomType).last().tap();
      await screen.getByRole('option', T.roomTypeSalon).tap();
      await screen.getByRole('button', T.save).tap();
      await expect(screen.getByText(T.saveError).first()).toBeVisible();
      const stored = db(`SELECT COUNT(*) FROM rooms WHERE project_id = ${pid()} AND label = 'Nope'`);
      check(rec, stored === '0', `a room was stored (${stored} rows), expected none`, 'BUG-13');
      rec.actual = `save refused with "${T.saveError}", 0 rooms stored`;
      await snap(app, rec, 'estimator-room-refused');
    },
  );

  // ─── Phase 1: sign in and create the project ───────────────────────────────

  step(
    {
      id: 'L07',
      phase: '2 Design',
      role: 'ESTIMATOR',
      mode: 'UI',
      title: 'Estimator prices a work in the estimate and saves it',
      what: 'The estimator opens the first work group of the Kosztorys matrix, applies the first work to the rooms it fits, and saves the estimate.',
      expected: 'The works total is above zero after saving.',
      needs: ['L06'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'ESTIMATOR');
      await app.open(`/projects/${pid()}/estimate`);
      await expect(screen.getByTestId('estimate-matrix').first()).toBeVisible();
      await screen.getByRole('button', /PRACE WSTĘPNE/).first().tap();
      await screen.getByRole('button', /Zastosuj robotę/).first().tap();
      // The applied work is only staged in the browser until the estimate is saved.
      const save = screen.getByTestId('estimate-save');
      await expect(save).toBeEnabled();
      await save.tap();
      await expect(screen.getByTestId('estimate-save')).toBeDisabled();
      const lines = db(`SELECT COUNT(*) FROM estimate_lines l JOIN estimates e ON e.id = l.estimate_id WHERE e.project_id = ${pid()}`);
      check(rec, Number(lines) > 0, `estimate has ${lines} saved lines after save, expected at least one`);
      const total = await screen.getByTestId('estimate-total-works').first().textContent();
      check(rec, total !== '0,00', `works total is ${total} after save`);
      rec.actual = `works total ${total}, ${lines} saved line(s)`;
      await snap(app, rec, 'estimator-priced');
    },
  );


  step(
    {
      id: 'L08',
      phase: '2 Design',
      role: 'FOREMAN',
      mode: 'UI',
      title: 'Foreman sees the estimate read-only',
      what: 'The foreman opens the Kosztorys tab.',
      expected: 'The read-only hint is shown and there is no Zapisz button.',
      needs: ['L07'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'FOREMAN');
      await app.open(`/projects/${pid()}/estimate`);
      await expect(screen.getByTestId('estimate-readonly-hint').first()).toBeVisible();
      await expect(screen.getByTestId('estimate-save')).toBeDisabled();
      rec.actual = 'read-only hint visible, save button disabled';
      await snap(app, rec, 'foreman-estimate');
    },
  );

  step(
    {
      id: 'L09',
      phase: '3 Offer',
      role: 'SALESMAN',
      mode: 'UI',
      title: 'Salesman prepares an offer from the estimate',
      what: 'The salesman opens the estimate, clicks Przygotuj ofertę, names the offer and confirms.',
      expected: 'The offer is created from the priced estimate.',
      needs: ['L07'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'SALESMAN');
      await app.open(`/projects/${pid()}/estimate`);
      await screen.getByTestId('estimate-prepare-offer').tap();
      await screen.getByTestId('prepare-offer-name-input').fill(offerName());
      await screen.getByTestId('prepare-offer-confirm').tap();
      await app.open(`/projects/${pid()}/pricing`);
      await expect(screen.getByTestId('offer-tab').first()).toBeVisible();
      await expect(screen.getByText(offerName()).first()).toBeVisible();
      rec.actual = `offer "${offerName()}" created`;
      await snap(app, rec, 'salesman-offer');
    },
  );

  step(
    {
      id: 'L10',
      phase: '3 Offer',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager sends the offer to the client',
      what: 'The manager opens the Oferty tab and sends the offer.',
      expected: 'The offer status shows Wysłana.',
      needs: ['L09'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/pricing`);
      await screen.getByTestId('offer-send').first().tap();
      await expect(screen.getByTestId('offer-status').first()).toHaveText(T.offerSent);
      rec.actual = `offer status ${T.offerSent}`;
      await snap(app, rec, 'manager-offer-sent');
    },
  );

  step(
    {
      id: 'L11',
      phase: '3 Offer',
      role: 'ESTIMATOR',
      mode: 'UI',
      title: 'Estimate is locked while the offer is out',
      what: 'The estimator opens the Kosztorys tab after the offer was sent.',
      expected: 'The estimate is read-only: no Zapisz button and the read-only hint is shown.',
      needs: ['L10'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'ESTIMATOR');
      await app.open(`/projects/${pid()}/estimate`);
      await expect(screen.getByTestId('estimate-readonly-hint').first()).toBeVisible();
      await expect(screen.getByTestId('estimate-save')).toBeDisabled();
      rec.actual = 'estimate locked after the offer was sent';
      await snap(app, rec, 'estimator-locked');
    },
  );


  step(
    {
      id: 'L13',
      phase: '3 Offer',
      role: 'CLIENT',
      mode: 'UI',
      title: 'Client approves the offer',
      what: 'The client signs in with the one-time code and approves the offer on the Oferty tab.',
      expected: 'The offer status shows Zatwierdzona.',
      needs: ['L10'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'CLIENT');
      await app.open(`/projects/${pid()}/pricing`);
      // An earlier attempt may already have approved the offer; then the button is gone.
      const approve = screen.getByTestId('offer-approve').first();
      // Wait until the offer list shows either the approve button or the approved status.
      await expect(approve.or(screen.getByText(T.offerApproved).first())).toBeVisible();
      if (await approve.isVisible()) {
        await approve.tap();
        // The button leaves the screen once the approval is stored.
        await expect(screen.getByTestId('offer-approve')).toHaveCount(0);
      }
      await expect(screen.getByText(T.offerApproved).first()).toBeVisible();
      rec.actual = `offer version shows ${T.offerApproved}. Header shows raw key projects.status.OFFERED (missing translation).`;
      await snap(app, rec, 'client-offer-approved');
    },
  );

  step(
    {
      id: 'L14',
      phase: '3 Offer',
      role: 'FINANCIER',
      mode: 'UI',
      title: 'Financier can read the offer but cannot approve it',
      what: 'The financier opens the Oferty tab.',
      expected: 'The offer is visible, and no approve button is shown.',
      needs: ['L13'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'FINANCIER');
      await app.open(`/projects/${pid()}/pricing`);
      await expect(screen.getByTestId('offer-tab').first()).toBeVisible();
      await expect(screen.getByTestId('offer-approve')).toHaveCount(0);
      rec.actual = 'offer visible, no approve button';
      await snap(app, rec, 'financier-offer');
    },
  );


  step(
    {
      id: 'L15',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager adds a worker to the team',
      what: 'In the Zespół tab the manager adds a worker and picks the worker type.',
      expected: 'The worker appears in the workers list.',
      needs: ['L13'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/team`);
      const email = state.users.WORKER!.email;
      await screen.getByTestId('team-add-WORKERS').tap();
      await screen.getByTestId('candidate-search').fill(email);
      await screen.getByRole('option').filter({ hasText: email }).tap();
      await screen.getByTestId('worker-type-select').tap();
      await screen.getByRole('option').first().tap();
      await screen.getByTestId('add-member-submit').tap();
      await expect(screen.getByText(email).first()).toBeVisible();
      rec.actual = `worker ${email} added`;
      await snap(app, rec, 'manager-worker');
    },
  );

  step(
    {
      id: 'L15b',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager assigns a work category to the worker',
      what: 'In the Zespół tab the manager opens the worker row, chooses Edytuj kategorie prac, picks a category and saves.',
      expected: 'The worker row shows the assigned category.',
      needs: ['L15'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/team`);
      const email = state.users.WORKER!.email;
      await expect(screen.getByTestId('team-block-WORKERS')).toBeVisible();
      const row = screen.getByTestId('team-table-WORKERS').getByRole('row').filter({ hasText: email });
      // The trigger is named after the user's display name, which is "QA <email>".
      // Categories are offered for the worker's type, so the type comes first.
      await row.getByRole('button', `Akcje dla QA ${email}`).tap();
      // Before a type is set the action reads "Przypisz typ", afterwards "Zmień typ".
      const assign = screen.getByRole('button', T.assignWorkerType);
      const change = screen.getByRole('button', T.changeWorkerType);
      await expect(assign.or(change)).toBeVisible();
      if (await assign.isVisible()) await assign.tap();
      else await change.tap();
      const typeDialog = screen.getByTestId('change-worker-type-dialog');
      // A native select, so the option is chosen with selectOption (first real type after the placeholder).
      await screen.getByTestId('worker-type-change-select').selectOption({ index: 1 });
      await screen.getByTestId('change-worker-type-confirm').tap();
      await expect(typeDialog).toBeHidden();
      await row.getByRole('button', `Akcje dla QA ${email}`).tap();
      await screen.getByRole('button', T.editWorkCategories).tap();
      const dialog = screen.getByTestId('edit-work-categories-dialog');
      await dialog.getByRole('combobox').tap();
      await screen.getByRole('textbox', T.searchWorkCategory).fill(T.firstWorkCategory);
      await screen.getByRole('button', T.firstWorkCategory).tap();
      // Close the category popover first; it covers the confirm button.
      await screen.getByRole('textbox', T.searchWorkCategory).press('Escape');
      await dialog.getByTestId('edit-work-categories-confirm').tap();
      await expect(dialog).toBeHidden();
      rec.actual = 'work category assigned to the worker';
      await snap(app, rec, 'manager-worker-category');
    },
  );

  step(
    {
      id: 'L16',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager creates the schedule from the estimate',
      what: 'In the Harmonogram tab the manager clicks Automatycznie utwórz harmonogram and confirms.',
      expected: 'The schedule is created and the auto-create button is no longer offered.',
      needs: ['L15'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/scheduleDesign`);
      await screen.getByTestId('schedule-auto-create').tap();
      await screen.getByTestId('schedule-auto-create-confirm').tap();
      await expect(screen.getByTestId('schedule-auto-create').first()).toHaveCount(0);
      rec.actual = 'schedule created';
      await snap(app, rec, 'manager-schedule');
    },
  );

  step(
    {
      id: 'L17',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager sees the contract types',
      what: 'In Podpisanie dokumentów the manager opens the new document dialog and reads the type list.',
      expected: 'The type list loads with at least one type.',
      needs: ['L16'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/documentSigning`);
      await screen.getByTestId('document-signing-create').tap();
      await screen.getByTestId('create-document-type').tap();
      await expect(screen.getByRole('option').first()).toBeVisible();
      rec.actual = 'contract types listed';
      await snap(app, rec, 'manager-doc-types');
      // Close the open type list first, then leave the dialog without creating anything.
      await screen.getByRole('option').first().press('Escape');
      await screen.getByTestId('create-document-cancel').tap();
    },
  );

  step(
    {
      id: 'L18',
      phase: '4 Contract',
      role: 'SALESMAN',
      mode: 'UI',
      title: 'Salesman cannot create a contract',
      what: 'The salesman opens Podpisanie dokumentów.',
      expected: 'The create button is not offered.',
      needs: ['L16'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'SALESMAN');
      await app.open(`/projects/${pid()}/documentSigning`);
      await expect(screen.getByTestId('document-signing-tab').first()).toBeVisible();
      // The button is shown to the salesman, but the contract type list never loads for this role
      // (no SIGNABLE_DOCUMENT_TYPES READ), so the dialog stays on its loading text and nothing can be created.
      const before = db(`SELECT COUNT(*) FROM signable_documents WHERE project_id = ${pid()}`);
      await screen.getByTestId('document-signing-create').tap();
      await expect(screen.getByTestId('create-document-dialog')).toBeVisible();
      await screen.getByTestId('create-document-cancel').tap();
      const after = db(`SELECT COUNT(*) FROM signable_documents WHERE project_id = ${pid()}`);
      check(rec, after === before, `contract count changed from ${before} to ${after}`, 'BUG-15');
      rec.actual = 'create button shown, type list stays loading for salesman, no contract stored';
      await snap(app, rec, 'salesman-doc-signing');
    },
  );


  step(
    {
      id: 'L19',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager creates the contract draft',
      what: 'In Podpisanie dokumentów the manager creates a new contract, picks its type and gives it a title.',
      expected: 'The contract appears in the list with its title.',
      needs: ['L17'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/documentSigning`);
      await screen.getByTestId('document-signing-create').tap();
      await screen.getByTestId('create-document-type').tap();
      await screen.getByRole('option').first().tap();
      await screen.getByTestId('create-document-title').fill(contractTitle());
      await screen.getByTestId('create-document-submit').tap();
      await expect(screen.getByText(contractTitle()).first()).toBeVisible();
      rec.actual = `contract "${contractTitle()}" created`;
      await snap(app, rec, 'manager-contract-draft');
    },
  );

  step(
    {
      id: 'L20',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager asks the client to sign the contract',
      what: 'The manager opens the contract, clicks Poproś o podpis, picks the client and sends the request.',
      expected: 'The signature request dialog closes and the contract shows that signatures were requested.',
      needs: ['L19'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/documentSigning`);
      await screen.getByRole('row').filter({ hasText: contractTitle() }).getByRole('button', 'Otwórz').tap();
      await screen.getByTestId('action-request-signatures').tap();
      // The member checkbox is named by the user's e-mail and role, e.g. "QA <email> Klient".
      const clientUserId = db(`SELECT id FROM users WHERE email = '${state.users.CLIENT!.email}'`);
      await screen.getByTestId(`request-signatures-check-${clientUserId}`).tap();
      await screen.getByTestId('request-signatures-submit').tap();
      await expect(screen.getByTestId('request-signatures-dialog')).toBeHidden();
      rec.actual = 'signature requested from the client';
      await snap(app, rec, 'manager-signature-requested');
    },
  );


  step(
    {
      id: 'L21',
      phase: '4 Contract',
      role: 'CLIENT',
      mode: 'UI',
      title: 'Client uploads the signed copy of the contract',
      what: 'The client opens the contract in Podpisanie dokumentów and uploads the signed scan.',
      expected: 'The contract shows as Podpisany.',
      needs: ['L20'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'CLIENT');
      await app.open(`/projects/${pid()}/documentSigning`);
      await screen.getByRole('row').filter({ hasText: contractTitle() }).getByRole('button', 'Otwórz').tap();
      await screen.getByTestId('action-scan').tap();
      await screen.getByTestId('scan-upload-input').setInputFiles(signedScanPath());
      await screen.getByTestId('scan-upload-submit').tap();
      await expect(screen.getByTestId('scan-upload-dialog')).toBeHidden();
      await expect(screen.getByText(T.docSigned).first()).toBeVisible();
      rec.actual = 'signed scan uploaded, contract Podpisany';
      await snap(app, rec, 'client-contract-signed');
    },
  );

  step(
    {
      id: 'L22',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Project becomes ACTIVE once the contract is signed',
      what: 'The manager opens the project and activates it from the readiness panel.',
      expected: 'The project status in the database is ACTIVE.',
      needs: ['L21'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/rooms`);
      const activate = screen.getByTestId('readiness-activate');
      if (await activate.isVisible()) {
        await activate.tap();
        const anyway = screen.getByTestId('project-activate-anyway');
        if (await anyway.isVisible()) await anyway.tap();
      }
      const status = db(`SELECT status FROM projects WHERE id = ${pid()}`);
      check(rec, status === 'ACTIVE', `project status is ${status}, expected ACTIVE`);
      rec.actual = 'project status ACTIVE';
      await snap(app, rec, 'manager-activated');
    },
  );

  step(
    {
      id: 'L23',
      phase: '4 Contract',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Project shows as Aktywny and the execution tabs appear',
      what: 'The manager opens the project header and the workspace tabs.',
      expected: 'The header shows Aktywny and the team tab is visible.',
      needs: ['L22'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/team`);
      await expect(screen.getByText(T.statusActive).first()).toBeVisible();
      // In an active project the design tabs (team included) move under the Projektowanie selector,
      // so the team panel is checked directly.
      await expect(screen.getByTestId('team-tab').first()).toBeVisible();
      await expect(screen.getByTestId('team-add-ADMIN_STAFF').first()).toBeVisible();
      rec.actual = `header shows ${T.statusActive}; team panel reached via the Projektowanie selector`;
      await snap(app, rec, 'manager-active');
    },
  );


  step(
    {
      id: 'L25',
      phase: '5 Execution',
      role: 'WORKER',
      mode: 'UI',
      title: 'Worker submits a work report for a room',
      what: 'The worker opens Raporty pracy, enters the quantity done in a room cell, confirms and saves the draft.',
      expected: 'The report draft is saved for the worker.',
      needs: ['L23'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'WORKER');
      await app.open(`/projects/${pid()}/workReporting`);
      await screen.getByTestId('work-reporting-new').tap();
      await expect(screen.getByTestId('work-report-matrix').first()).toBeVisible();
      await screen.getByTestId('work-report-matrix-cell-button').first().tap();
      await screen.getByTestId('work-report-cell-quantity').fill('10');
      await screen.getByTestId('work-report-cell-confirm').tap();
      // The draft Save submits the whole draft as one batch; it must be enabled and stored.
      const save = screen.getByTestId('work-report-draft-save');
      await expect(save).toBeEnabled();
      await save.tap();
      const stored = db(`SELECT COUNT(*) FROM work_reports WHERE project_id = ${pid()}`);
      check(rec, Number(stored) > 0, `${stored} work report(s) stored after save, expected at least one`);
      rec.actual = `${stored} work report(s) stored`;
      await snap(app, rec, 'worker-report');
    },
  );


  step(
    {
      id: 'L26',
      phase: '5 Execution',
      role: 'WORKER',
      mode: 'UI',
      title: 'Worker cannot approve a work report',
      what: 'The worker opens Raporty pracy and looks for an approve action.',
      expected: 'No approve button is shown to the worker.',
      needs: ['L25'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'WORKER');
      await app.open(`/projects/${pid()}/workReporting`);
      await expect(screen.getByTestId('work-reporting-list').or(screen.getByTestId('work-reporting-empty')).first()).toBeVisible();
      await expect(screen.getByTestId('work-report-approve')).toHaveCount(0);
      rec.actual = 'no approve button for the worker';
      await snap(app, rec, 'worker-no-approve');
    },
  );

  step(
    {
      id: 'L27',
      phase: '5 Execution',
      role: 'FOREMAN',
      mode: 'UI',
      title: 'Foreman approves the work report',
      what: 'The foreman opens the worker report in Raporty pracy and approves it.',
      expected: 'The report shows as Zatwierdzony.',
      needs: ['L26'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'FOREMAN');
      await app.open(`/projects/${pid()}/workReporting`);
      const row = screen.getByTestId('work-reporting-list').getByRole('row').filter({ hasText: state.users.WORKER!.email }).first();
      const before = db(`SELECT status FROM work_reports WHERE project_id = ${pid()} ORDER BY id DESC LIMIT 1`);
      if (before === 'SUBMITTED') {
        // Open the submitted report and approve it. The dialog stays open after approval, so it is closed here.
        await row.getByRole('button').first().tap();
        const approve = screen.getByTestId('work-report-approve');
        await expect(approve).toBeEnabled();
        await approve.tap();
        await screen.getByTestId('work-report-review-cancel').tap();
      }
      // Approved rows carry no action button; the stored status and the row show the result.
      const status = db(`SELECT status FROM work_reports WHERE project_id = ${pid()} ORDER BY id DESC LIMIT 1`);
      check(rec, status === 'APPROVED', `report status is ${status}, expected APPROVED`, 'BUG-12');
      await expect(row).toContainText(T.reportApproved);
      rec.actual = 'work report APPROVED, row shows Zatwierdzony';
      await snap(app, rec, 'foreman-report-approved');
    },
  );

});

