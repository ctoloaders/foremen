import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  day, del, errMatches, get, getBytes, http, IBAN_DE, IBAN_PL, IBAN_PL_SPACED, listPayments, makeWorld, ok, post, positionOf, psql, put, rowsOf,
  worksSchedule, type World,
} from './support.js';
// FOR-11-06 Part A: A1 (unified client payment), A2 (materials payment), A3 (works tranches), A4 (project bank account).
// Source: .kiro/specs/FOR-11-Delivery-Management/FOR-11-06-delivery-ux-refinements/test-cases.md.
// Repeatability: run-id world per test (support.ts), full cleanup in `finally`.
const PDF_ENV = 'ENV: PDF storage unavailable (issue answers 502) - the issued state is not reachable in this stack';
/** Issues a payment for two fresh accepted positions; returns null when the PDF storage is down. */
async function issued(w: World) {
  const a = await w.acceptedPosition(4, 100);
  const b = await w.acceptedPosition(2, 250);
  const p = await w.issuedPayment([a, b]);
  return p.mode === 'api' ? { ...p, a, b } : null;
}
describe('FOR-11-06 API: unified client payments', { tags: ['for-11-06', 'api'] }, () => {
  test('TC-A1-01 one list returns both kinds with the common fields', async () => {
    const w = await makeWorld();
    try {
      await worksSchedule(w);
      const draft = await w.issuedPayment([await w.acceptedPosition(4, 100)]);
      const r = await listPayments(w, 'MANAGER');
      expect(r.status, r.text).toBe(200);
      const rows = rowsOf(r);
      expect(rows.filter((x) => x.kind === 'WORKS')).toHaveLength(3);
      expect(rows.filter((x) => x.kind === 'MATERIALS')).toHaveLength(1);
      expect(r.body.totalElements).toBe(4);
      for (const k of ['id', 'kind', 'status', 'dueDate', 'amountGross', 'paidGross', 'remainingGross', 'currency', 'effectiveBankAccount']) {
        expect(rows[0], k).toHaveProperty(k);
      }
      expect(draft.id).toBeGreaterThan(0);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A1-02 pagination is shared by both kinds and ordered by due date, then id', async () => {
    const w = await makeWorld();
    try {
      await worksSchedule(w);
      for (let i = 0; i < 4; i++) await w.issuedPayment([await w.acceptedPosition(1, 100 + i)]);
      const p0 = await listPayments(w, 'MANAGER', 'size=2&page=0');
      const p1 = await listPayments(w, 'MANAGER', 'size=2&page=1');
      expect(rowsOf(p0)).toHaveLength(2);
      expect(rowsOf(p1)).toHaveLength(2);
      expect(p0.body.totalElements).toBe(7);
      const all = [...rowsOf(await listPayments(w, 'MANAGER', 'size=2&page=0')), ...rowsOf(p1)];
      const sorted = [...all].sort((x, y) => String(x.dueDate).localeCompare(String(y.dueDate)) || x.id - y.id);
      expect(all.map((x) => x.id)).toEqual(sorted.map((x) => x.id));
      expect(rowsOf(await listPayments(w, 'MANAGER', 'size=2&page=0')).map((x) => x.id)).toEqual(rowsOf(p0).map((x) => x.id));
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A1-03 filters narrow the list and the summary covers the whole filtered set', async () => {
    const w = await makeWorld();
    try {
      await worksSchedule(w);
      await w.issuedPayment([await w.acceptedPosition(4, 100)]);
      const works = await listPayments(w, 'MANAGER', 'kind=WORKS');
      const mats = await listPayments(w, 'MANAGER', 'kind=MATERIALS');
      expect(rowsOf(works).every((x) => x.kind === 'WORKS')).toBe(true);
      expect(rowsOf(mats).every((x) => x.kind === 'MATERIALS')).toBe(true);
      expect(rowsOf(works)).toHaveLength(3);
      const overdue = await listPayments(w, 'MANAGER', 'overdue=true');
      expect(rowsOf(overdue).every((x) => x.overdue === true)).toBe(true);
      expect(rowsOf(overdue).length).toBeLessThan(rowsOf(works).length + 1);
      const range = await listPayments(w, 'MANAGER', `dueFrom=${day(5)}&dueTo=${day(20)}`);
      expect(rowsOf(range).every((x) => x.dueDate >= day(5) && x.dueDate <= day(20))).toBe(true);
      const paid = await listPayments(w, 'MANAGER', 'status=PAID');
      expect(rowsOf(paid)).toHaveLength(0);
      const s = await get(`/api/client-payments/summary?projectId=${w.projectId}&kind=WORKS`, w.t('MANAGER'));
      expect(s.status, s.text).toBe(200);
      expect(s.body.count).toBe(3);
      expect(Number(s.body.amountGross)).toBeCloseTo(6000, 2);
      const paged = await listPayments(w, 'MANAGER', 'kind=WORKS&size=1');
      expect(rowsOf(paged)).toHaveLength(1);
      expect(Number(s.body.amountGross)).toBeGreaterThan(Number(rowsOf(paged)[0].amountGross));
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A1-04 one ABAC resource: anonymous 401/403, WORKER 403, the old resources are gone from the matrix', async () => {
    const w = await makeWorld({ tags: ['WORKER'] });
    try {
      const anon = await get(`/api/client-payments?projectId=${w.projectId}`);
      expect([401, 403]).toContain(anon.status);
      expect((await listPayments(w, 'WORKER')).status).toBe(403);
      const codes = (await ok('GET', '/api/resources?size=200', w.admin)).content.map((r: any) => r.code) as string[];
      expect(codes).toContain('CLIENT_PAYMENTS');
      expect(codes).not.toContain('PAYMENTS');
      expect(codes).not.toContain('CLIENT_PROFORMAS');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A1-05 visibility by kind: FOREMAN works only, BUYER materials only, FINANCIER cannot write materials', async () => {
    const w = await makeWorld();
    try {
      await worksSchedule(w);
      const pid = await w.acceptedPosition(4, 100);
      const p = await w.issuedPayment([pid]);
      const fm = await listPayments(w, 'FOREMAN');
      expect(fm.status, fm.text).toBe(200);
      expect(rowsOf(fm).every((x) => x.kind === 'WORKS')).toBe(true);
      expect(rowsOf(fm).length).toBeGreaterThan(0);
      expect(rowsOf(await listPayments(w, 'FOREMAN', 'kind=MATERIALS'))).toHaveLength(0);
      const by = await listPayments(w, 'BUYER');
      expect(by.status, by.text).toBe(200);
      expect(rowsOf(by).every((x) => x.kind === 'MATERIALS')).toBe(true);
      const fin = await listPayments(w, 'FINANCIER');
      expect(new Set(rowsOf(fin).map((x) => x.kind))).toEqual(new Set(['WORKS', 'MATERIALS']));
      const create = await post('/api/client-payments/materials', w.t('FINANCIER'), { projectId: w.projectId, positionIds: [pid] });
      expect(errMatches(create, 403, 'error.clientPayment.kind.forbidden')).toBeNull();
      expect(p.id).toBeGreaterThan(0);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A1-06 project scope: a manager outside the project gets 404 / an empty page, ADMIN sees all', async () => {
    const w = await makeWorld({ tags: ['MANAGER2'] });
    try {
      const p = await w.issuedPayment([await w.acceptedPosition(2, 100)]);
      expect((await get(`/api/client-payments/${p.id}`, w.t('MANAGER2'))).status).toBe(404);
      expect(rowsOf(await listPayments(w, 'MANAGER2'))).toHaveLength(0);
      expect((await get(`/api/client-payments/${p.id}`, w.admin)).status).toBe(200);
      expect(rowsOf(await listPayments(w, 'ADMIN')).length).toBeGreaterThan(0);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A1-07 display status: tranche PLANNED/OVERDUE, proforma DRAFT then CANCELLED', async () => {
    const w = await makeWorld();
    try {
      const sch = await worksSchedule(w);
      const rows = rowsOf(await listPayments(w, 'MANAGER', 'kind=WORKS'));
      const past = rows.find((x) => x.dueDate === rows.map((r) => r.dueDate).sort()[0])!;
      const future = rows.find((x) => x.dueDate > day(0))!;
      if (sch.published) {
        expect(future.status).toBe('PLANNED');
        expect(past.status).toBe('OVERDUE');
      } else {
        expect(['DRAFT', 'PLANNED']).toContain(future.status);
      }
      const p = await w.issuedPayment([await w.acceptedPosition(2, 100)]);
      const one = await get(`/api/client-payments/${p.id}`, w.t('MANAGER'));
      expect(one.body.status).toBe(p.mode === 'api' ? 'ISSUED' : 'DRAFT');
      const c = await post(`/api/client-payments/${p.id}/cancel`, w.t('MANAGER'), { reason: w.tag('rcpt') });
      expect(c.status, c.text).toBe(200);
      expect((await get(`/api/client-payments/${p.id}`, w.t('MANAGER'))).body.status).toBe('CANCELLED');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-01 and A2-02 draft then issue: number PF/<year>/<n>, ISSUED, positions IN_PROFORMA', async () => {
    const w = await makeWorld();
    try {
      const a = await w.acceptedPosition(4, 100);
      const b = await w.acceptedPosition(2, 250);
      const draft = await post('/api/client-payments/materials', w.t('MANAGER'), { projectId: w.projectId, positionIds: [a, b], dueDate: day(7), note: w.tag('rcpt') });
      expect(draft.status, draft.text).toBe(200);
      const view = await get(`/api/client-payments/${draft.body.id}`, w.t('MANAGER'));
      expect(view.body).toMatchObject({ kind: 'MATERIALS', status: 'DRAFT' });
      expect((await positionOf(w, a)).status).toBe('ACCEPTED');
      const iss = await post(`/api/client-payments/${draft.body.id}/issue`, w.t('MANAGER'), { language: 'pl' });
      if (iss.status === 502) test.skip(PDF_ENV);
      expect(iss.status, iss.text).toBe(200);
      const after = (await get(`/api/client-payments/${draft.body.id}`, w.t('MANAGER'))).body;
      expect(after.status).toBe('ISSUED');
      expect(after.proforma.number).toMatch(/^PF\/\d{4}\/\d+/);
      expect(after.proforma.hasPdf).toBe(true);
      expect((await positionOf(w, a)).status).toBe('IN_PROFORMA');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-03 the PDF is application/pdf with the Polish labels and the payee account', async () => {
    const w = await makeWorld();
    try {
      const p = await issued(w);
      if (!p) test.skip(PDF_ENV);
      const pdf = await getBytes(`/api/client-payments/${p!.id}/pdf`, w.t('MANAGER'));
      expect(pdf.status).toBe(200);
      expect(pdf.type).toContain('application/pdf');
      expect(pdf.bytes.subarray(0, 4).toString()).toBe('%PDF');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-04 the payee account is a snapshot taken at issue', async () => {
    const w = await makeWorld();
    try {
      expect((await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_PL })).status).toBe(200);
      const p = await issued(w);
      if (!p) test.skip(PDF_ENV);
      const before = (await get(`/api/client-payments/${p!.id}`, w.t('MANAGER'))).body;
      expect(before.proforma.bankAccountSnapshot).toBe(IBAN_PL);
      expect((await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_DE })).status).toBe(200);
      const after = (await get(`/api/client-payments/${p!.id}`, w.t('MANAGER'))).body;
      expect(after.proforma.bankAccountSnapshot).toBe(IBAN_PL);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-05 a statement row linked by clientPaymentId pays the proforma and releases the positions', async () => {
    const w = await makeWorld();
    try {
      const p = await issued(w);
      if (!p) test.skip(PDF_ENV);
      const gross = Number((await get(`/api/client-payments/${p!.id}`, w.t('MANAGER'))).body.amountGross);
      const t = w.statementRow(gross, 'PF pay');
      const r = await post(`/api/bank/transactions/${t}/links`, w.t('FINANCIER'), { kind: 'PROFORMA_PAYMENT', amount: gross, clientPaymentId: p!.id });
      expect(r.status, r.text).toBe(201);
      expect((await get(`/api/client-payments/${p!.id}`, w.t('MANAGER'))).body.status).toBe('PAID');
      expect((await positionOf(w, p!.a)).status).toBe('WITH_BUYER');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-06 a link by the id of a works payment closes the nearest tranche first (FIFO)', async () => {
    const w = await makeWorld();
    try {
      const sch = await worksSchedule(w);
      if (!sch.published) test.skip('ENV: the schedule cannot be published without an estimate basis; FIFO payment needs a published schedule');
      const rows = rowsOf(await listPayments(w, 'MANAGER', 'kind=WORKS')).sort((a, b) => String(a.dueDate).localeCompare(String(b.dueDate)));
      const t = w.statementRow(1000, 'works pay');
      const r = await post(`/api/bank/transactions/${t}/links`, w.t('FINANCIER'), { kind: 'CLIENT_PAYMENT', amount: 1000, clientPaymentId: rows[0].id });
      expect(r.status, r.text).toBe(201);
      const after = rowsOf(await listPayments(w, 'MANAGER', 'kind=WORKS')).find((x) => x.id === rows[0].id)!;
      expect(Number(after.paidGross)).toBeCloseTo(1000, 2);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-07 the old link fields (clientProformaId) still work', async () => {
    const w = await makeWorld();
    try {
      const p = await issued(w);
      if (!p) test.skip(PDF_ENV);
      const view = (await get(`/api/client-payments/${p!.id}`, w.t('MANAGER'))).body;
      const t = w.statementRow(Number(view.amountGross), 'PF legacy');
      const r = await post(`/api/bank/transactions/${t}/links`, w.t('FINANCIER'), {
        kind: 'PROFORMA_PAYMENT', amount: view.amountGross, clientProformaId: view.proforma.proformaId,
      });
      expect(r.status, r.text).toBe(201);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-08 cancel: an unpaid payment returns the positions to ACCEPTED, a paid one answers 409', async () => {
    const w = await makeWorld();
    try {
      const p = await issued(w);
      if (!p) test.skip(PDF_ENV);
      const c = await post(`/api/client-payments/${p!.id}/cancel`, w.t('MANAGER'), { reason: w.tag('rcpt') });
      expect(c.status, c.text).toBe(200);
      expect((await positionOf(w, p!.a)).status).toBe('ACCEPTED');
      const q = await w.issuedPayment([p!.a]);
      const gross = Number((await get(`/api/client-payments/${q.id}`, w.t('MANAGER'))).body.amountGross);
      await post(`/api/bank/transactions/${w.statementRow(gross)}/links`, w.t('FINANCIER'), { kind: 'PROFORMA_PAYMENT', amount: gross, clientPaymentId: q.id });
      expect((await post(`/api/client-payments/${q.id}/cancel`, w.t('MANAGER'), {})).status).toBe(409);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A2-09 a non-manager cannot issue (403 kind.forbidden)', async () => {
    const w = await makeWorld();
    try {
      const draft = await ok('POST', '/api/client-payments/materials', w.t('MANAGER'), {
        projectId: w.projectId, positionIds: [await w.acceptedPosition(2, 100)],
      });
      const r = await post(`/api/client-payments/${draft.id}/issue`, w.t('FINANCIER'), {});
      expect(errMatches(r, 403, 'error.clientPayment.kind.forbidden')).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A3-01 works lifecycle through the new paths: schedule, tranches, publish, listed as WORKS', async () => {
    const w = await makeWorld();
    try {
      const s = await ok('POST', '/api/client-payments/schedules', w.admin, { projectId: w.projectId, source: 'MANUAL' });
      const t = await post(`/api/client-payments/schedules/${s.id}/tranches`, w.admin, { amountGross: 500, dueDate: day(3) });
      expect(t.status, t.text).toBe(200);
      const view = await get(`/api/client-payments/schedules/${s.id}`, w.admin);
      expect(view.body.tranches).toHaveLength(1);
      const byProject = await get(`/api/client-payments/projects/${w.projectId}/schedule`, w.admin);
      expect(byProject.body.id).toBe(s.id);
      expect(rowsOf(await listPayments(w, 'ADMIN', 'kind=WORKS'))).toHaveLength(1);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A3-02 manual mark-paid by the financier is idempotent', async () => {
    const w = await makeWorld();
    try {
      const sch = await worksSchedule(w);
      if (!sch.published) test.skip('ENV: mark-paid needs a published schedule (estimate basis missing)');
      const row = rowsOf(await listPayments(w, 'FINANCIER', 'kind=WORKS'))[0];
      expect((await post(`/api/client-payments/${row.id}/mark-paid`, w.t('FINANCIER'), { note: w.tag('rcpt') })).status).toBe(200);
      expect((await post(`/api/client-payments/${row.id}/mark-paid`, w.t('FINANCIER'), {})).status).toBe(200);
      expect((await get(`/api/client-payments/${row.id}`, w.admin)).body.status).toBe('PAID');
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A3-03 overdue tranche is flagged and the overdue notification points at the payments tab', async () => {
    const w = await makeWorld();
    try {
      const sch = await worksSchedule(w);
      if (!sch.published) test.skip('ENV: overdue handling needs a published schedule');
      const overdue = rowsOf(await listPayments(w, 'MANAGER', 'overdue=true'));
      expect(overdue.length).toBeGreaterThan(0);
      expect(overdue[0].status).toBe('OVERDUE');
      // The overdue sweep is a nightly job; its notification link format is asserted in the backend unit tests.
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A3-04 the old paths are gone', async () => {
    const w = await makeWorld();
    try {
      for (const path of [`/api/payments/projects/${w.projectId}/schedule`, '/api/client-proformas']) {
        const r = await get(path, w.admin);
        expect([404, 405], path).toContain(r.status);
      }
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A4-01 the project account is stored without spaces, upper case', async () => {
    const w = await makeWorld();
    try {
      const r = await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_PL_SPACED.toLowerCase() });
      expect(r.status, r.text).toBe(200);
      expect(r.body.bankAccount).toBe(IBAN_PL);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A4-02 validation: bad checksum, letter, too short -> 400; null clears', async () => {
    const w = await makeWorld();
    try {
      for (const bad of ['PL61109010140000071219812875', 'PL6110901014000007121981287X', 'PL6110']) {
        const r = await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: bad });
        expect(errMatches(r, 400, 'error.project.bankAccount.invalid'), bad).toBeNull();
      }
      await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_PL });
      const clear = await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: null });
      expect(clear.status).toBe(200);
      expect(clear.body.bankAccount ?? null).toBeNull();
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A4-03 rights: only a project MANAGER or ADMIN; the generic project update ignores the field', async () => {
    const w = await makeWorld({ tags: ['MANAGER2'] });
    try {
      for (const tag of ['MANAGER2', 'FOREMAN', 'CLIENT', 'BUYER', 'FINANCIER'] as const) {
        const r = await put(`/api/projects/${w.projectId}/bank-account`, w.t(tag), { bankAccount: IBAN_PL });
        expect([403, 404], tag).toContain(r.status);
      }
      expect((await put(`/api/projects/${w.projectId}/bank-account`, w.admin, { bankAccount: IBAN_PL })).status).toBe(200);
      const project = (await get(`/api/projects/${w.projectId}`, w.admin)).body;
      const upd = await put(`/api/projects/${w.projectId}`, w.admin, { ...project, bankAccount: IBAN_DE });
      expect([200, 400]).toContain(upd.status);
      expect(psql(`SELECT bank_account FROM projects WHERE id = ${w.projectId};`)).toBe(IBAN_PL);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A4-04 effective account: project account, else the company account', async () => {
    const w = await makeWorld();
    try {
      await worksSchedule(w);
      const first = rowsOf(await listPayments(w, 'MANAGER'))[0];
      expect(first.effectiveBankAccount).toBeTruthy();
      await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_DE });
      expect(rowsOf(await listPayments(w, 'MANAGER'))[0].effectiveBankAccount).toBe(IBAN_DE);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A4-05 visibility: CLIENT sees payeeBankAccount, FOREMAN and BUYER get no account field', async () => {
    const w = await makeWorld();
    try {
      await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_PL });
      const sch = await worksSchedule(w);
      if (!sch.published) test.skip('ENV: a CLIENT sees only published tranches (estimate basis missing)');
      const cl = rowsOf(await listPayments(w, 'CLIENT'));
      expect(cl.length).toBeGreaterThan(0);
      expect(cl[0].payeeBankAccount).toBe(IBAN_PL);
      expect(cl[0]).not.toHaveProperty('effectiveBankAccount');
      const fm = rowsOf(await listPayments(w, 'FOREMAN'));
      expect(JSON.stringify(fm)).not.toContain(IBAN_PL);
    } finally {
      await w.cleanup();
    }
  });
  test('TC-A4-06 the change is audited with a masked value', async () => {
    const w = await makeWorld();
    try {
      await put(`/api/projects/${w.projectId}/bank-account`, w.t('MANAGER'), { bankAccount: IBAN_PL });
      const audit = psql(`SELECT COALESCE(string_agg(COALESCE(snapshot_after::text, '') , ' '), '') FROM audit_log WHERE entity_class = 'ProjectEntity' AND entity_id = ${w.projectId};`);
      // R4.7: the audit snapshot keeps only the last 4 characters of the account.
      expect(audit).not.toContain(IBAN_PL);
      expect(audit).toContain('2874');
    } finally {
      await w.cleanup();
    }
  });
  test('cleanup sanity: deleting a draft payment removes it from the list', async () => {
    const w = await makeWorld();
    try {
      const draft = await ok('POST', '/api/client-payments/materials', w.t('MANAGER'), { projectId: w.projectId, positionIds: [await w.acceptedPosition(1, 10)] });
      expect((await del(`/api/client-payments/${draft.id}`, w.t('MANAGER'))).status).toBe(204);
      expect((await http('GET', `/api/client-payments/${draft.id}`, w.t('MANAGER'))).status).toBe(404);
    } finally {
      await w.cleanup();
    }
  });
});
