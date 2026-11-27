// Project lifecycle, one project, every role, in timeline order.
// Step definitions and their expectations live in lifecycle/03-ROLE-TIMELINES.md. Each step declares
// `needs`; a step whose needs did not pass is reported BLOCKED and not run.
import { test, describe, beforeAll, afterAll, beforeEach } from '@e2e-dev/web';
import { expect, credentials } from 'e2e';
import { deleteProject, raw, roleIdByCode } from '../support/api.js';
import { createUser, deleteUser } from '../support/users.js';
import {
  begin,
  call,
  CATALOG,
  check,
  expectHttp,
  finish,
  RUN_ID,
  saveState,
  snap,
  snippet,
  state,
  unmetNeeds,
  upload,
  writeReport,
  type StepDef,
  type StepRecord,
} from './support/lc.js';
import { APP_LOCALE_INIT, LOCALE, T } from './support/i18n.js';
import { latestOtpCode } from '../support/client-otp.js';

const SCAN_PDF = new TextEncoder().encode('%PDF-1.4\n% lifecycle signature scan\n%%EOF\n');
const TEAM: Array<[string, string]> = [
  ['MANAGER', 'MANAGER'],
  ['ESTIMATOR', 'ESTIMATOR'],
  ['SALESMAN', 'SALESMAN'],
  ['FOREMAN', 'FOREMAN'],
  ['FINANCIER', 'FINANCIER'],
  ['BUYER', 'BUYER'],
];

function step(def: StepDef, body: (fx: any, rec: StepRecord) => Promise<void>) {
  test(`${def.id} [${def.role}] ${def.title}`, async (fx: any) => {
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

/** Opens the project in the browser, signed in as a provisioned role or ADMIN. */
async function signIn(app: any, screen: any, role: string): Promise<void> {
  if (role === 'CLIENT') {
    // Clients have no password (FOR-03-05): request a one-time code and enter it, as the client does.
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
const id = (key: string) => state.ids[key];

describe('project lifecycle', () => {
  beforeEach(async ({ browser }) => {
    // The app picks its UI language from localStorage, before any page script runs.
    await browser.addInitScript(APP_LOCALE_INIT);
  });

  beforeAll(async () => {
    // Runs again when the runner reloads the module; the persisted state keeps users and ids from the first load.
    if (!state.users.MANAGER) {
      for (const [, code] of TEAM) state.users[code] = createUser(code, code.toLowerCase());
      state.users.CLIENT = createUser('CLIENT', 'client');
      state.users.WORKER = createUser('WORKER', 'worker');
      state.users.OTHER = createUser('OTHER', 'other');
      state.projectName = `QA-LC-${RUN_ID.replace(/[^A-Za-z0-9-]/g, '').slice(0, 40)}`;
      saveState();
    }
  });

  // ─── Phase 1: foundation and project creation ───────────────────────────────

  step(
    {
      id: 'L01',
      phase: '1 Foundation',
      role: 'ADMIN',
      mode: 'UI',
      title: 'Admin signs in and sees the System menu',
      what: 'The admin signs in and looks at the navigation.',
      expected: 'Menu items Użytkownicy, Role and Audyt are visible.',
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
      mode: 'API',
      title: 'Admin creates the project with its team and client',
      what: 'The admin creates a project and adds MANAGER, ESTIMATOR, SALESMAN, FOREMAN, FINANCIER and BUYER to the team, plus the existing CLIENT user.',
      expected: '201, status DRAFT, six team members and one client.',
    },
    async (_fx, rec) => {
      const members = [];
      for (const [, code] of TEAM) {
        members.push({ userId: state.users[code]!.id, projectRoleId: await roleIdByCode(code) });
      }
      const res = await call('ADMIN', 'POST', '/api/projects', {
        name: state.projectName,
        area: 120,
        members,
        client: { existingClientUserId: state.users.CLIENT!.id },
      });
      expectHttp(rec, res, [200, 201], 'create project');
      state.projectId = res.body.id as number;
      check(rec, res.body.status === 'DRAFT', `status is ${res.body.status}, expected DRAFT`);
      const memberIds = (res.body.members as Array<{ userId: number }>).map((m) => m.userId);
      for (const [, code] of TEAM) {
        check(rec, memberIds.includes(state.users[code]!.id), `team member ${code} missing from project`);
      }
      rec.actual = `project ${state.projectId} created, status DRAFT, ${memberIds.length} members (team of 6 plus the creator), 1 client`;
    },
  );

  step(
    {
      id: 'L03',
      phase: '1 Foundation',
      role: 'ADMIN',
      mode: 'UI',
      title: 'Admin sees the new project in the project list as Szkic',
      what: 'The admin opens the project list and finds the new project.',
      expected: 'The project row shows the name and the status Szkic.',
      needs: ['L02'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'ADMIN');
      // The project list is paged and this database holds many QA projects, so the new project is checked on its workspace.
      await app.open(`/projects/${pid()}/rooms`);
      await expect(screen.getByText(state.projectName!).first()).toBeVisible();
      await expect(screen.getByText(T.statusDraft).first()).toBeVisible();
      rec.actual = `workspace of ${state.projectName} shows Szkic`;
      await snap(app, rec, 'admin-project-workspace');
    },
  );

  // ─── Phase 2: design, rooms and estimate ────────────────────────────────────

  step(
    {
      id: 'L04',
      phase: '2 Design',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager opens the project and sees the design tabs',
      what: 'The manager signs in and opens the project workspace.',
      expected: 'Tabs Pomieszczenia, Kosztorys, Oferty, Zespół, Harmonogram and Podpisanie dokumentów are visible.',
      needs: ['L03'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/rooms`);
      for (const key of ['rooms', 'estimate', 'pricing', 'team', 'scheduleDesign', 'documentSigning']) {
        await expect(screen.getByTestId(`workspace-tab-${key}`)).toBeVisible();
      }
      rec.actual = 'six design tabs visible';
      await snap(app, rec, 'manager-design-tabs');
    },
  );

  step(
    {
      id: 'L05',
      phase: '2 Design',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager creates two rooms',
      what: 'The manager adds a living room and a bedroom with their floor and wall areas.',
      expected: '200 for each room.',
      needs: ['L02'],
    },
    async (_fx, rec) => {
      const rooms: Array<[string, number]> = [
        ['Salon', 1],
        ['Sypialnia', 2],
      ];
      state.ids.roomIds = [];
      for (const [label, roomTypeId] of rooms) {
        const res = await call('MANAGER', 'POST', '/api/rooms', {
          projectId: pid(),
          roomTypeId,
          label,
          floorArea: 30,
          wallArea: 60,
          perimeter: 22,
          ceilingHeight: 2.6,
        });
        expectHttp(rec, res, [200, 201], `create room ${label}`);
        state.ids.roomIds.push(res.body.id);
      }
      rec.actual = `rooms ${state.ids.roomIds.join(', ')} created`;
    },
  );

  step(
    {
      id: 'L06',
      phase: '2 Design',
      role: 'ESTIMATOR',
      mode: 'API',
      title: 'Estimator cannot create rooms but can read the estimate',
      what: 'The estimator tries to add a room, then reads the project estimate.',
      expected: 'Room create returns 403. Estimate read returns 200.',
      needs: ['L05'],
    },
    async (_fx, rec) => {
      const denied = await call('ESTIMATOR', 'POST', '/api/rooms', { projectId: pid(), roomTypeId: 1, label: 'Nope' });
      expectHttp(rec, denied, [403], 'estimator room create');
      const est = await call('ESTIMATOR', 'GET', `/api/estimates/project/${pid()}`);
      expectHttp(rec, est, [200], 'estimator estimate read');
      state.ids.estimateId = est.body.id;
      rec.actual = `room create 403, estimate read 200 (estimate ${state.ids.estimateId})`;
    },
  );

  step(
    {
      id: 'L07',
      phase: '2 Design',
      role: 'ESTIMATOR',
      mode: 'API',
      title: 'Estimator builds a priced estimate',
      what: 'The estimator adds a priced work line and a quantity for each room.',
      expected: 'Line and room quantities return 200. The estimate total is above 0.',
      needs: ['L05'],
    },
    async (_fx, rec) => {
      const price = await call('ADMIN', 'GET', '/api/work-prices?size=1');
      expectHttp(rec, price, [200], 'read work price');
      const priced = price.body.content[0];
      const item = await call('ADMIN', 'GET', `/api/work-items/${priced.workItemId}`);
      expectHttp(rec, item, [200], 'read work item');
      state.ids.workItemId = priced.workItemId;
      state.ids.workCategoryId = item.body.workCategoryId;
      const line = await call('ESTIMATOR', 'POST', '/api/estimate-lines', {
        estimateId: state.ids.estimateId,
        workItemId: priced.workItemId,
        unitId: item.body.unitId,
        unitPrice: priced.netPrice,
        lineNo: 1,
      });
      expectHttp(rec, line, [200], 'add estimate line');
      state.ids.lineId = line.body.id;
      for (const roomId of state.ids.roomIds) {
        const q = await call('ESTIMATOR', 'POST', '/api/estimate-line-room-qty', { lineId: state.ids.lineId, roomId, quantity: 30 });
        expectHttp(rec, q, [200], `room quantity for room ${roomId}`);
      }
      const est = await call('ESTIMATOR', 'GET', `/api/estimates/project/${pid()}`);
      expectHttp(rec, est, [200], 're-read estimate');
      check(rec, Number(est.body.totalNet) > 0, `estimate totalNet is ${est.body.totalNet}, expected above 0`);
      rec.actual = `line ${state.ids.lineId} with room quantities, totalNet ${est.body.totalNet}`;
    },
  );

  step(
    {
      id: 'L08',
      phase: '2 Design',
      role: 'FOREMAN',
      mode: 'API',
      title: 'Foreman has a read-only estimate',
      what: 'The foreman tries to add a line to the estimate.',
      expected: '403. The foreman holds ESTIMATE READ only.',
      needs: ['L07'],
    },
    async (_fx, rec) => {
      const res = await call('FOREMAN', 'POST', '/api/estimate-lines', {
        estimateId: state.ids.estimateId,
        workItemId: state.ids.workItemId,
        unitId: 1,
        unitPrice: 1,
        lineNo: 99,
      });
      expectHttp(rec, res, [403], 'foreman add line');
    },
  );

  // ─── Phase 3: offer and client approval ─────────────────────────────────────

  step(
    {
      id: 'L09',
      phase: '3 Offer',
      role: 'SALESMAN',
      mode: 'API',
      title: 'Salesman prepares an offer from the estimate',
      what: 'The salesman prepares a new offer for the project.',
      expected: '200, offer DRAFT.',
      needs: ['L07'],
    },
    async (_fx, rec) => {
      const res = await call('SALESMAN', 'POST', `/api/offers/project/${pid()}/prepare`, {});
      expectHttp(rec, res, [200], 'prepare offer');
      state.ids.offerId = res.body.offer.offerId;
      check(rec, res.body.offer.status === 'DRAFT', `offer status ${res.body.offer.status}, expected DRAFT`);
      rec.actual = `offer ${state.ids.offerId} DRAFT`;
    },
  );

  step(
    {
      id: 'L10',
      phase: '3 Offer',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager sends the offer to the client',
      what: 'The manager opens the Oferty tab and presses Wyślij.',
      expected: 'The offer status reads Wysłana.',
      needs: ['L09'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/pricing`);
      await screen.getByTestId('offer-send').first().tap();
      await expect(screen.getByTestId('offer-status').first()).toContainText(T.offerSent);
      rec.actual = 'offer status Wysłana';
      await snap(app, rec, 'offer-sent');
    },
  );

  step(
    {
      id: 'L11',
      phase: '3 Offer',
      role: 'MANAGER',
      mode: 'API',
      title: 'Project moves to OFFERED after the offer is sent',
      what: 'The manager reads the project status after sending the offer.',
      expected: 'Status OFFERED (FOR-05-07 TC-LC-01). Actual status is DRAFT.',
      needs: ['L10'],
    },
    async (_fx, rec) => {
      const res = await call('MANAGER', 'GET', `/api/projects/${pid()}`);
      expectHttp(rec, res, [200], 'read project');
      check(rec, res.body.status === 'OFFERED', `project status is ${res.body.status}, expected OFFERED`, 'BUG-01');
      rec.actual = `status ${res.body.status}`;
    },
  );

  step(
    {
      id: 'L12',
      phase: '3 Offer',
      role: 'ESTIMATOR',
      mode: 'API',
      title: 'Estimate stays editable while the offer is out',
      what: 'The estimator adds a line after the offer was sent. The sent offer keeps its snapshot. The service lock starts at ACTIVE (FOR-05-07 R20.2, checked in L12b).',
      expected: '200. Edits are allowed while the project is not ACTIVE.',
      needs: ['L10'],
    },
    async (_fx, rec) => {
      const res = await call('ESTIMATOR', 'POST', '/api/estimate-lines', {
        estimateId: state.ids.estimateId,
        workItemId: state.ids.workItemId,
        unitId: 1,
        unitPrice: 1,
        lineNo: 98,
      });
      expectHttp(rec, res, [200], 'estimator edit while offer out');
    },
  );

  step(
    {
      id: 'L13',
      phase: '3 Offer',
      role: 'CLIENT',
      mode: 'UI',
      title: 'Client approves the offer',
      what: 'The client signs in, opens the offer and presses Zatwierdź.',
      expected: 'The offer is APPROVED. Read back through the API, because the client view does not render the offer-status label.',
      needs: ['L10'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'CLIENT');
      await app.open(`/projects/${pid()}/pricing`);
      await screen.getByTestId('offer-approve').first().tap();
      await snap(app, rec, 'client-after-approve');
      const res = await call('CLIENT', 'GET', `/api/offers/${state.ids.offerId}`);
      expectHttp(rec, res, [200], 'client reads offer');
      check(rec, res.body.status === 'APPROVED', `offer status ${res.body.status}, expected APPROVED`);
      rec.actual = 'offer APPROVED (read through the API after the UI approval)';
    },
  );

  step(
    {
      id: 'L14',
      phase: '3 Offer',
      role: 'FINANCIER',
      mode: 'API',
      title: 'Financier can read the offer but cannot approve it',
      what: 'The financier tries to approve the offer.',
      expected: '403. The financier holds OFFERS READ only.',
      needs: ['L13'],
    },
    async (_fx, rec) => {
      const res = await call('FINANCIER', 'POST', `/api/offers/${state.ids.offerId}/approve`);
      expectHttp(rec, res, [403], 'financier approve offer');
    },
  );

  // ─── Phase 4: team, schedule, contract ─────────────────────────────────────

  step(
    {
      id: 'L15',
      phase: '4 Team and contract',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager adds a worker and assigns a work category',
      what: 'The manager adds the worker to the team and gives them the work category of the priced line.',
      expected: '201 on add, 200 on assigning the category.',
      needs: ['L02'],
    },
    async (_fx, rec) => {
      const add = await call('MANAGER', 'POST', '/api/project-members', {
        userId: state.users.WORKER!.id,
        projectId: pid(),
        projectRoleId: await roleIdByCode('WORKER'),
      });
      expectHttp(rec, add, [200, 201], 'add worker');
      const cat = await call('MANAGER', 'PATCH', '/api/project-members', {
        userId: state.users.WORKER!.id,
        projectId: pid(),
        workCategoryIds: [state.ids.workCategoryId],
      });
      expectHttp(rec, cat, [200], 'assign work category');
      rec.actual = `worker added, category ${state.ids.workCategoryId} assigned`;
    },
  );

  step(
    {
      id: 'L16',
      phase: '4 Team and contract',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager auto-creates the schedule',
      what: 'The manager presses Utwórz automatycznie on the schedule tab.',
      expected: '200, crew size 1 or more.',
      needs: ['L15'],
    },
    async (_fx, rec) => {
      const res = await call('MANAGER', 'POST', `/api/project-schedules/auto-create?projectId=${pid()}`, { version: 0 });
      expectHttp(rec, res, [200], 'auto-create schedule');
      check(rec, Number(res.body.schedule.crewSize) >= 1, `crew size ${res.body.schedule.crewSize}, expected 1 or more`);
      rec.actual = `schedule created, crew size ${res.body.schedule.crewSize}`;
    },
  );

  step(
    {
      id: 'L17',
      phase: '4 Team and contract',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager can list the signable document types',
      what: 'The manager opens the document signing tab and needs the list of document types to create a contract.',
      expected: '200, the list contains CONTRACT_WORKS. Actual: 403.',
      needs: ['L02'],
    },
    async (_fx, rec) => {
      const res = await call('MANAGER', 'GET', '/api/signable-document-types');
      expectHttp(rec, res, [200], 'list document types', 'BUG-03');
      const codes = (res.body as Array<{ code: string }>).map((t) => t.code);
      check(rec, codes.includes('CONTRACT_WORKS'), `list has no CONTRACT_WORKS (${codes.length} types)`, 'BUG-03');
    },
  );

  step(
    {
      id: 'L18',
      phase: '4 Team and contract',
      role: 'SALESMAN',
      mode: 'API',
      title: 'Salesman cannot create a contract',
      what: 'The salesman tries to create a CONTRACT_WORKS document.',
      expected: '403 error.document.operation.forbidden (signing guard). The matrix grants CREATE; see O-1.',
      needs: ['L02'],
    },
    async (_fx, rec) => {
      const types = await call('ADMIN', 'GET', '/api/signable-document-types');
      expectHttp(rec, types, [200], 'admin reads document types');
      const contract = (types.body as Array<{ id: number; code: string }>).find((t) => t.code === 'CONTRACT_WORKS')!;
      const res = await call('SALESMAN', 'POST', '/api/signable-documents', {
        projectId: pid(),
        documentTypeId: contract.id,
        templateLocale: 'PL',
        title: 'Salesman contract attempt',
      });
      expectHttp(rec, res, [403], 'salesman create contract');
    },
  );

  step(
    {
      id: 'L19',
      phase: '4 Team and contract',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager creates the contract draft and generates its body',
      what: 'The manager creates a CONTRACT_WORKS document for the project and generates the body from the template.',
      expected: '200 on create, 200 on generate.',
      needs: ['L13'],
    },
    async (_fx, rec) => {
      const types = await call('ADMIN', 'GET', '/api/signable-document-types');
      expectHttp(rec, types, [200], 'admin reads document types');
      const contract = (types.body as Array<{ id: number; code: string }>).find((t) => t.code === 'CONTRACT_WORKS')!;
      const create = await call('MANAGER', 'POST', '/api/signable-documents', {
        projectId: pid(),
        documentTypeId: contract.id,
        templateLocale: 'PL',
        title: `Umowa ${state.projectName}`,
      });
      expectHttp(rec, create, [200], 'create contract');
      state.ids.documentId = create.body.id;
      const gen = await call('MANAGER', 'POST', `/api/signable-documents/${state.ids.documentId}/generate`);
      expectHttp(rec, gen, [200], 'generate contract body');
      rec.actual = `contract ${state.ids.documentId} generated`;
    },
  );

  step(
    {
      id: 'L20',
      phase: '4 Team and contract',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager requests the client signature on the contract',
      what: 'The manager sends the contract to the client for a wet-ink signature.',
      expected: '200, status PENDING_SIGNATURES. The PDF freeze stores in Google Cloud Storage (BLK-1, fixed by re-authenticating ADC).',
      needs: ['L19'],
    },
    async (_fx, rec) => {
      const res = await call('MANAGER', 'POST', `/api/signable-documents/${state.ids.documentId}/request-signatures`, {
        method: 'PRINT',
        level: 'SES',
        signers: [{ signerUserId: state.users.CLIENT!.id, signerRole: 'CLIENT' }],
      });
      expectHttp(rec, res, [200], 'request signatures');
      check(rec, res.body.status === 'PENDING_SIGNATURES', `status ${res.body.status}`);
    },
  );

  step(
    {
      id: 'L21',
      phase: '4 Team and contract',
      role: 'CLIENT',
      mode: 'API',
      title: 'Client signs the contract with a scanned copy',
      what: 'The client uploads the signed scan of the contract.',
      expected: '200, status SIGNED.',
      needs: ['L20'],
    },
    async (_fx, rec) => {
      const res = await upload('CLIENT', `/api/signable-documents/${state.ids.documentId}/sign`, SCAN_PDF, 'contract-scan.pdf', 'application/pdf');
      expectHttp(rec, res, [200], 'client sign contract');
    },
  );

  // ─── Phase 5: activation (workaround) ──────────────────────────────────────

  step(
    {
      id: 'L22',
      phase: '5 Activation',
      role: 'MANAGER',
      mode: 'API',
      title: 'Project becomes ACTIVE once the contract is signed',
      what: 'The signed CONTRACT_* document and the approved offer activate the project (FOR-05-08 R10). No force is used.',
      expected: 'The project status reads ACTIVE.',
      needs: ['L21', 'L13', 'L15', 'L16'],
    },
    async (_fx, rec) => {
      const res = await call('MANAGER', 'GET', `/api/projects/${pid()}`);
      expectHttp(rec, res, [200], 'read project');
      check(rec, res.body.status === 'ACTIVE', `status ${res.body.status}, expected ACTIVE after the contract was signed`);
      rec.actual = 'project ACTIVE after the contract was signed';
    },
  );

  step(
    {
      id: 'L12b',
      phase: '5 Activation',
      role: 'ESTIMATOR',
      mode: 'API',
      title: 'Estimate is locked once the project is ACTIVE',
      what: 'The estimator tries to add a line to the estimate of the active project.',
      expected: '409 error.estimate.locked (FOR-05-07 R20.2, TC-LOCK-01).',
      needs: ['L22'],
    },
    async (_fx, rec) => {
      const res = await call('ESTIMATOR', 'POST', '/api/estimate-lines', {
        estimateId: state.ids.estimateId,
        workItemId: state.ids.workItemId,
        unitId: 1,
        unitPrice: 1,
        lineNo: 97,
      });
      expectHttp(rec, res, [409], 'estimator edit on ACTIVE project', 'BUG-02');
    },
  );

  step(
    {
      id: 'L23',
      phase: '5 Activation',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Project shows as Aktywny and the execution tabs appear',
      what: 'The manager opens the project list and then the workspace.',
      expected: 'The row shows Aktywny. Raporty pracy, Płatności, Aneksy and Komunikacja are visible.',
      needs: ['L22'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/workReporting`);
      await expect(screen.getByText(T.statusActive).first()).toBeVisible();
      for (const key of ['workReporting', 'clientPayments', 'amendments', 'clientCommunication']) {
        await expect(screen.getByTestId(`workspace-tab-${key}`)).toBeVisible();
      }
      rec.actual = 'Aktywny in list, four execution tabs visible';
      await snap(app, rec, 'manager-active-project');
    },
  );

  step(
    {
      id: 'L24',
      phase: '5 Activation',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager can reach the team tab of an active project',
      what: 'The manager opens the team tab after the project became ACTIVE.',
      expected: 'The team tab is shown. Actual: the tab is hidden in execution. Team changes stay possible until COMPLETED (FOR-05-09).',
      needs: ['L23'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/team`);
      await expect(screen.getByTestId('team-tab')).toBeVisible();
      rec.actual = 'team tab visible';
    },
  );

  // ─── Phase 6: execution ────────────────────────────────────────────────────

  step(
    {
      id: 'L25',
      phase: '6 Execution',
      role: 'WORKER',
      mode: 'API',
      title: 'Worker submits a work report for a room',
      what: 'The worker reports five square metres of the priced work in the living room.',
      expected: '200 and one created report.',
      needs: ['L22', 'L15'],
    },
    async (_fx, rec) => {
      const today = new Date().toISOString().slice(0, 10);
      const res = await call('WORKER', 'POST', '/api/work-reports/batch', {
        reports: [
          {
            projectId: pid(),
            workItemId: state.ids.workItemId,
            workerId: state.users.WORKER!.id,
            performedFrom: today,
            performedTo: today,
            rooms: [{ roomId: state.ids.roomIds[0], quantity: 5 }],
            note: 'lifecycle run',
          },
        ],
      });
      expectHttp(rec, res, [200], 'submit work report');
      state.ids.reportId = res.body.created[0];
      rec.actual = `report ${state.ids.reportId} submitted`;
    },
  );

  step(
    {
      id: 'L26',
      phase: '6 Execution',
      role: 'WORKER',
      mode: 'API',
      title: 'Worker cannot approve a work report',
      what: 'The worker tries to approve their own report.',
      expected: '403. Approval belongs to the foreman.',
      needs: ['L25'],
    },
    async (_fx, rec) => {
      const res = await call('WORKER', 'POST', `/api/work-reports/${state.ids.reportId}/approve`);
      expectHttp(rec, res, [403], 'worker approve report');
    },
  );

  step(
    {
      id: 'L27',
      phase: '6 Execution',
      role: 'FOREMAN',
      mode: 'API',
      title: 'Foreman approves the work report',
      what: 'The foreman approves the worker report.',
      expected: '200, status APPROVED.',
      needs: ['L25'],
    },
    async (_fx, rec) => {
      const res = await call('FOREMAN', 'POST', `/api/work-reports/${state.ids.reportId}/approve`);
      expectHttp(rec, res, [200], 'foreman approve report');
      rec.actual = 'report approved';
    },
  );

  step(
    {
      id: 'L28',
      phase: '6 Execution',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager sees progress on the plan versus actual tab',
      what: 'The manager opens Plan/wykonanie.',
      expected: 'Overall completion reads above 0%.',
      needs: ['L27'],
    },
    async ({ app, screen }, rec) => {
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/planActual`);
      await expect(screen.getByTestId('plan-actual-overall')).toContainText(T.completionText);
      rec.actual = 'completion above 0%';
      await snap(app, rec, 'plan-actual');
    },
  );

  step(
    {
      id: 'L29',
      phase: '6 Execution',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager creates and publishes a three-tranche payment schedule',
      what: 'The manager creates a generated schedule, splits the offer total into three equal tranches, and publishes it.',
      expected: '200 on each call. The schedule has three tranches.',
      needs: ['L22'],
    },
    async (_fx, rec) => {
      const create = await call('MANAGER', 'POST', '/api/client-payments/schedules', { projectId: pid(), source: 'GENERATED' });
      expectHttp(rec, create, [200], 'create schedule');
      const sid = create.body.id as number;
      state.ids.scheduleId = sid;
      const gen = await call('MANAGER', 'POST', `/api/client-payments/schedules/${sid}/generate`, { strategy: 'EQUAL', count: 3, startDate: new Date().toISOString().slice(0, 10), periodicity: 'MONTHLY' });
      expectHttp(rec, gen, [200], 'generate tranches');
      const pub = await call('MANAGER', 'POST', `/api/client-payments/schedules/${sid}/publish`, {});
      expectHttp(rec, pub, [200], 'publish schedule');
      check(rec, pub.body.tranches?.length === 3, `${pub.body.tranches?.length} tranches, expected 3`);
      state.ids.trancheId = pub.body.tranches[0].id;
      rec.actual = `schedule ${sid} published with 3 tranches`;
    },
  );

  step(
    {
      id: 'L30',
      phase: '6 Execution',
      role: 'FINANCIER',
      mode: 'API',
      title: 'Financier records a payment on the first tranche',
      what: 'The financier marks the first tranche as paid.',
      expected: '200.',
      needs: ['L29'],
    },
    async (_fx, rec) => {
      const res = await call('FINANCIER', 'POST', `/api/client-payments/schedules/${state.ids.scheduleId}/transactions`, { amountGross: 100, feeGross: 0, receivedAt: new Date().toISOString(), note: 'lifecycle run' });
      expectHttp(rec, res, [200], 'mark tranche paid');
    },
  );

  step(
    {
      id: 'L31',
      phase: '6 Execution',
      role: 'CLIENT',
      mode: 'UI',
      title: 'Client posts a question in the communication tab',
      what: 'The client writes a question in the Komunikacja tab and sends it.',
      expected: 'The message appears in the thread.',
      needs: ['L23'],
    },
    async ({ app, screen }, rec) => {
      const text = `Pytanie lifecycle ${state.projectName}`;
      await signIn(app, screen, 'CLIENT');
      await app.open(`/projects/${pid()}/clientCommunication`);
      await screen.getByLabel(T.composerLabel).fill(text);
      await screen.getByTestId('client-communication-composer-send').tap();
      await expect(screen.getByText(text).first()).toBeVisible();
      rec.actual = 'message in thread';
      await snap(app, rec, 'client-question');
    },
  );

  step(
    {
      id: 'L32',
      phase: '6 Execution',
      role: 'MANAGER',
      mode: 'UI',
      title: 'Manager replies to the client question',
      what: 'The manager opens the communication tab and replies.',
      expected: 'The reply appears in the thread.',
      needs: ['L31'],
    },
    async ({ app, screen }, rec) => {
      const text = `Odpowiedź lifecycle ${state.projectName}`;
      await signIn(app, screen, 'MANAGER');
      await app.open(`/projects/${pid()}/clientCommunication`);
      await screen.getByLabel(T.composerLabel).fill(text);
      await screen.getByTestId('client-communication-composer-send').tap();
      await expect(screen.getByText(text).first()).toBeVisible();
      rec.actual = 'reply in thread';
      await snap(app, rec, 'manager-reply');
    },
  );

  step(
    {
      id: 'L33',
      phase: '6 Execution',
      role: 'CLIENT',
      mode: 'API',
      title: 'Client cannot record a payment',
      what: 'The client tries to mark a tranche as paid.',
      expected: '403. Payments belong to the financier.',
      needs: ['L29'],
    },
    async (_fx, rec) => {
      const res = await call('CLIENT', 'POST', `/api/client-payments/schedules/${state.ids.scheduleId}/transactions`, { amountGross: 100, feeGross: 0, receivedAt: new Date().toISOString(), note: 'client attempt' });
      expectHttp(rec, res, [403], 'client mark paid');
    },
  );

  step(
    {
      id: 'L34',
      phase: '6 Execution',
      role: 'FINANCIER',
      mode: 'API',
      title: 'Financier pays the worker for the approved report',
      what: 'The financier creates a payout for the worker for the reporting period.',
      expected: '201 with a payout.',
      needs: ['L27'],
    },
    async (_fx, rec) => {
      const today = new Date().toISOString().slice(0, 10);
      const res = await call('FINANCIER', 'POST', '/api/worker-payouts', {
        workerId: state.users.WORKER!.id,
        payoutDate: today,
        periodFrom: today,
        periodTo: today,
        paymentChannel: 'BANK_TRANSFER',
        amount: 1,
        note: 'lifecycle run',
      });
      expectHttp(rec, res, [200, 201], 'create payout');
    },
  );

  step(
    {
      id: 'L35',
      phase: '6 Execution',
      role: 'WORKER',
      mode: 'API',
      title: 'Worker cannot read company payroll',
      what: 'The worker tries to open the company payroll.',
      expected: '403. Workers have no PAYROLL grant.',
      needs: ['L34'],
    },
    async (_fx, rec) => {
      const today = new Date().toISOString().slice(0, 10);
      const res = await call('WORKER', 'GET', `/api/payroll/company?from=${today}&to=${today}`);
      expectHttp(rec, res, [403], 'worker payroll read');
    },
  );

  // ─── Phase 7: amendments (blocked by BLK-1) ────────────────────────────────

  step(
    {
      id: 'L36',
      phase: '7 Amendments',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager opens an amendment',
      what: 'The manager creates an amendment for the active project.',
      expected: '200, status OPEN.',
      needs: ['L22'],
    },
    async (_fx, rec) => {
      const res = await call('MANAGER', 'POST', '/api/amendments', { projectId: pid(), note: 'lifecycle run' });
      expectHttp(rec, res, [200, 201], 'create amendment', 'BUG-10');
      state.ids.amendmentId = res.body.id;
    },
  );

  step(
    {
      id: 'L36b',
      phase: '7 Amendments',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager adds an additional-work line to the amendment',
      what: 'The manager adds one additional-work line (two square metres at 9.95) to the open amendment. An empty amendment cannot be sent (FOR-06-09 R1.1).',
      expected: '200, the amendment holds one line.',
      needs: ['L36'],
    },
    async (_fx, rec) => {
      const res = await call('MANAGER', 'POST', `/api/amendments/${state.ids.amendmentId}/lines?projectId=${pid()}`, {
        changeType: 'ADDITIONAL_WORK',
        workItemId: state.ids.workItemId,
        roomId: state.ids.roomIds[0],
        deltaQuantity: 2,
        extraUnitPrice: 9.95,
        description: 'lifecycle run',
      });
      expectHttp(rec, res, [200], 'add amendment line');
      check(rec, (res.body.lines ?? []).length === 1, `${(res.body.lines ?? []).length} lines, expected 1`);
      rec.actual = 'amendment holds one additional-work line';
    },
  );

  step(
    {
      id: 'L37',
      phase: '7 Amendments',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager sends the amendment for signing',
      what: 'The manager sends the amendment to the client for signature.',
      expected: '200, status SENT_FOR_SIGNING.',
      needs: ['L36b'],
    },
    async (_fx, rec) => {
      // PRINT (wet-ink scan) is used because ONLINE has no live provider (FOR-05-08 gap G-4). AdES is the default level.
      const res = await call('MANAGER', 'POST', `/api/amendments/${state.ids.amendmentId}/send-for-signing`, { method: 'PRINT', level: 'AdES' });
      expectHttp(rec, res, [200], 'send amendment for signing');
    },
  );

  step(
    {
      id: 'L38',
      phase: '7 Amendments',
      role: 'CLIENT',
      mode: 'API',
      title: 'Client signs the amendment',
      what: 'The client uploads the signed scan of the amendment.',
      expected: '200.',
      needs: ['L37'],
    },
    async (_fx, rec) => {
      // An amendment is signed as a SignableDocument of type AMENDMENT (FOR-06-09 reuses the FOR-05-08 engine).
      const list = await call('MANAGER', 'GET', `/api/signable-documents?projectId=${pid()}`);
      expectHttp(rec, list, [200], 'list project documents');
      const items = (Array.isArray(list.body) ? list.body : list.body.content) as Array<{ id: number; documentTypeCode: string; status: string }>;
      const doc = items.find((d) => d.documentTypeCode === 'AMENDMENT' && d.status === 'PENDING_SIGNATURES');
      check(rec, doc !== undefined, `no pending AMENDMENT document among ${items.length} project documents`);
      const res = await upload('CLIENT', `/api/signable-documents/${doc!.id}/sign`, SCAN_PDF, 'amendment-scan.pdf', 'application/pdf');
      expectHttp(rec, res, [200], 'client sign amendment document');
    },
  );

  step(
    {
      id: 'L38b',
      phase: '7 Amendments',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager countersigns the amendment as executor',
      what: 'The amendment signers are the client and the executor representative, the manager (FOR-06-09 R1.4). The manager signs the scan. The document then reads SIGNED.',
      expected: '200, and the amendment document status is SIGNED.',
      needs: ['L38'],
    },
    async (_fx, rec) => {
      const list = await call('MANAGER', 'GET', `/api/signable-documents?projectId=${pid()}`);
      expectHttp(rec, list, [200], 'list project documents');
      const items = (Array.isArray(list.body) ? list.body : list.body.content) as Array<{ id: number; documentTypeCode: string; status: string }>;
      const doc = items.find((d) => d.documentTypeCode === 'AMENDMENT' && d.status === 'PENDING_SIGNATURES');
      check(rec, doc !== undefined, 'no pending AMENDMENT document to countersign');
      const res = await upload('MANAGER', `/api/signable-documents/${doc!.id}/sign`, SCAN_PDF, 'amendment-executor.pdf', 'application/pdf');
      expectHttp(rec, res, [200], 'manager countersign amendment');
      const after = await call('MANAGER', 'GET', `/api/signable-documents/${doc!.id}`);
      expectHttp(rec, after, [200], 'read amendment document');
      check(rec, after.body.status === 'SIGNED', `amendment document status ${after.body.status}, expected SIGNED`);
    },
  );

  // ─── Phase 8: delivery access (FOR-11) ─────────────────────────────────────

  step(
    {
      id: 'L39',
      phase: '8 Delivery access',
      role: 'BUYER',
      mode: 'API',
      title: 'Buyer reads shipments and external orders',
      what: 'The buyer opens the shipments and external orders for the project.',
      expected: '200 for both.',
      needs: ['L15'],
    },
    async (_fx, rec) => {
      const ship = await call('BUYER', 'GET', `/api/shipments?projectId=${pid()}`);
      expectHttp(rec, ship, [200], 'buyer shipments');
      const ext = await call('BUYER', 'GET', `/api/external-orders?projectId=${pid()}`);
      expectHttp(rec, ext, [200], 'buyer external orders');
    },
  );

  step(
    {
      id: 'L40',
      phase: '8 Delivery access',
      role: 'FOREMAN',
      mode: 'API',
      title: 'Foreman reads goods due for receipt',
      what: 'The foreman opens the goods receipt list for the project.',
      expected: '200.',
      needs: ['L22'],
    },
    async (_fx, rec) => {
      const res = await call('FOREMAN', 'GET', `/api/goods-receipts/to-receive?projectId=${pid()}`);
      expectHttp(rec, res, [200], 'foreman goods to receive');
    },
  );

  step(
    {
      id: 'L41',
      phase: '8 Delivery access',
      role: 'FOREMAN',
      mode: 'API',
      title: 'Foreman cannot create a purchase request',
      what: 'The foreman tries to create a purchase request from positions.',
      expected: '403. Purchasing belongs to the buyer and the manager.',
      needs: ['L22'],
    },
    async (_fx, rec) => {
      const res = await call('FOREMAN', 'POST', '/api/purchase-requests/from-positions', { positionIds: [1] });
      expectHttp(rec, res, [403], 'foreman purchase request');
    },
  );

  // ─── Phase 9: finalization and oversight ───────────────────────────────────

  step(
    {
      id: 'L42',
      phase: '9 Finalization',
      role: 'MANAGER',
      mode: 'API',
      title: 'Manager sets the project to Zakończony',
      what: 'The manager saves the project with status COMPLETED, the same PUT the edit form sends. The edit form is not used here because the project list is paged and the row cannot be reached reliably (coverage gap). No spec defines the gate for this (gap G-1).',
      expected: '200 and status COMPLETED.',
      needs: ['L30', 'L32'],
    },
    async (_fx, rec) => {
      const cur = await call('MANAGER', 'GET', `/api/projects/${pid()}`);
      expectHttp(rec, cur, [200], 'read project');
      const res = await call('MANAGER', 'PUT', `/api/projects/${pid()}`, { ...cur.body, status: 'COMPLETED' });
      expectHttp(rec, res, [200], 'set COMPLETED');
      check(rec, res.body.status === 'COMPLETED', `status ${res.body.status}, expected COMPLETED`);
      rec.actual = 'status COMPLETED';
    },
  );

  step(
    {
      id: 'L43',
      phase: '9 Finalization',
      role: 'MANAGER',
      mode: 'API',
      title: 'Work reporting is closed once the project is COMPLETED',
      what: 'The manager tries to submit a new work report after the project was completed.',
      expected: '409 or 403, because the reporting spec makes the report tables read-only after COMPLETED (FOR-06-01 R10).',
      needs: ['L42'],
    },
    async (_fx, rec) => {
      const today = new Date().toISOString().slice(0, 10);
      const res = await call('MANAGER', 'POST', '/api/work-reports/batch', {
        reports: [
          {
            projectId: pid(),
            workItemId: state.ids.workItemId,
            workerId: state.users.WORKER!.id,
            performedFrom: today,
            performedTo: today,
            rooms: [{ roomId: state.ids.roomIds[0], quantity: 1 }],
            note: 'after completion',
          },
        ],
      });
      expectHttp(rec, res, [403, 409], 'report after COMPLETED');
    },
  );

  step(
    {
      id: 'L44',
      phase: '9 Finalization',
      role: 'ADMIN',
      mode: 'API',
      title: 'Admin reads the audit trail',
      what: 'The admin opens the audit log.',
      expected: '200 with entries.',
      needs: ['L42'],
    },
    async (_fx, rec) => {
      const res = await call('ADMIN', 'GET', '/api/audit?size=20');
      expectHttp(rec, res, [200], 'read audit');
      check(rec, (res.body.content ?? []).length > 0, 'audit list is empty');
      rec.actual = `${res.body.content.length} audit entries`;
    },
  );

  step(
    {
      id: 'L45',
      phase: '9 Finalization',
      role: 'ADMIN',
      mode: 'API',
      title: 'Deactivated user loses access',
      what: 'The admin deactivates a throwaway user, then that user tries to sign in.',
      expected: 'Sign-in is refused with 403. Actual: sign-in succeeds (FOR-02-07 R4).',
      needs: ['L02'],
    },
    async (_fx, rec) => {
      const victim = createUser('WORKER', 'deact');
      state.users.VICTIM = victim;
      const got = await call('ADMIN', 'GET', `/api/users/${victim.id}`);
      expectHttp(rec, got, [200], 'read user');
      const put = await call('ADMIN', 'PUT', `/api/users/${victim.id}`, { ...got.body, active: false });
      expectHttp(rec, put, [200], 'deactivate user');
      const login = await raw('POST', '/api/auth/login', { body: JSON.stringify({ email: victim.email, password: victim.password }) });
      expectHttp(rec, login, [403], 'deactivated login', 'BUG-06');
    },
  );

  step(
    {
      id: 'L46',
      phase: '9 Finalization',
      role: 'CLIENT',
      mode: 'API',
      title: 'Client cannot sign in with a password',
      what: 'The client tries to sign in with the password. Clients use one-time codes (FOR-03-05).',
      expected: '401 invalid credentials, the same answer as a wrong password, so a client account is not revealed (BUG-07).',
      needs: ['L02'],
    },
    async (_fx, rec) => {
      const res = await raw('POST', '/api/auth/login', {
        body: JSON.stringify({ email: state.users.CLIENT!.email, password: state.users.CLIENT!.password }),
      });
      expectHttp(rec, res, [401], 'client password login', 'BUG-07');
    },
  );

  step(
    {
      id: 'L47',
      phase: '9 Finalization',
      role: 'OTHER',
      mode: 'API',
      title: 'Outsider cannot see the project',
      what: 'A user with no project membership opens the project.',
      expected: '403 for OTHER, who holds no PROJECTS grant. 404 for a FOREMAN who is not a project member (FOR-03-04a).',
      needs: ['L02'],
    },
    async (_fx, rec) => {
      const res = await call('OTHER', 'GET', `/api/projects/${pid()}`);
      expectHttp(rec, res, [403], 'OTHER read project');
      if (!state.users.NONMEMBER) state.users.NONMEMBER = createUser('FOREMAN', 'nonmember');
      const nm = await call('NONMEMBER', 'GET', `/api/projects/${pid()}`);
      expectHttp(rec, nm, [404], 'non-member FOREMAN read project');
    },
  );

  step(
    {
      id: 'L48',
      phase: '9 Finalization',
      role: 'ADMIN',
      mode: 'API',
      title: 'Admin deletes the finished project',
      what: 'The admin deletes the project at the end of its lifecycle. The project has rooms, so the database keeps a foreign key to it.',
      expected: '409 error.project.hasDependents, because the project still owns rooms. Before the fix the answer was 500 (BUG-09).',
      needs: ['L42'],
    },
    async (_fx, rec) => {
      const res = await call('ADMIN', 'DELETE', `/api/projects/${pid()}`);
      expectHttp(rec, res, [409], 'admin delete project', 'BUG-09');
      check(rec, res.body.code === 'error.project.hasDependents', `code ${res.body.code}, expected error.project.hasDependents`);
    },
  );

  afterAll(async () => {
    writeReport(CATALOG);
    // Cleanup is opt-in. A worker reload also ends the realm, and tearing down then would delete the live run's data.
    if (process.env.LC_CLEANUP !== '1') return;
    if (state.projectId) {
      try {
        await deleteProject(state.projectId);
      } catch (e) {
        console.warn(`teardown: project ${state.projectId} not deleted: ${(e as Error).message}`);
      }
    }
    for (const u of Object.values(state.users)) {
      if (!u) continue;
      try {
        deleteUser(u);
      } catch (e) {
        console.warn(`teardown: user ${u.id} not deleted: ${(e as Error).message}`);
      }
    }
  });
});
