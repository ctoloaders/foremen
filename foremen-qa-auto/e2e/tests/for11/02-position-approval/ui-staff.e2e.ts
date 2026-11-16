import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import { Env, loginUi, psql } from './support.js';

// FOR-11-02 Part B6 (manager screens). Source: FOR-11-02 test-cases.md.
// Repeatability: generator + teardown (Env). The staff list shows only positions that already have a round
// (BUG-1), so every position here is first sent through the API (and, where needed, rejected to get it back to PLANNED).
const CL = '/api/position-approvals/client';
const tabUrl = (env: Env) => `/projects/${env.projectA}/positionApprovals`;
const row = (screen: any, id: number) => screen.getByTestId(`position-row-${id}`);

async function openStaffTab(env: Env, app: any, screen: any, role = 'manager') {
  await loginUi(app, screen, env.users[role]);
  await app.open(tabUrl(env));
  await expect(screen.getByTestId('staff-approvals-view')).toBeVisible();
}

describe('FOR-11-02 B6 manager', { tags: ['for-11-02', 'ui'] }, () => {
  test('TC-B6-01 send a block: internal data, not-eligible list, confirmation', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, block: 1 });
      const p2 = await env.position('2', { sale: 50, purchase: 30, block: 1 });
      await env.send([p1.id, p2.id]);
      for (const p of [p1, p2]) {
        const r = await env.as('client', 'POST', `${CL}/rounds/${await env.roundId(p.id)}/reject`, { comment: 'qa' });
        expect(r.status, r.text).toBeLessThan(300);
      }
      psql(`UPDATE purchase_positions SET sale_price = NULL WHERE id = :id;`, { id: String(p2.id) }); // p2 is no longer eligible

      await openStaffTab(env, app, screen);
      await expect(row(screen, p1.id).getByTestId('internal-purchase')).toContainText('61,37');
      await expect(row(screen, p1.id).getByTestId('internal-margin')).toBeVisible();
      await screen.getByTestId('send-block-button').tap();
      const dlg = screen.getByTestId('send-block-dialog');
      await expect(dlg).toBeVisible();
      await expect(screen.getByTestId('send-count')).toContainText('1');
      await expect(screen.getByTestId('not-eligible')).toContainText('Brak ceny sprzedaży lub stawki VAT');
      await screen.getByTestId('send-confirm').tap();
      await expect(screen.getByText('Wysłano do decyzji').first()).toBeVisible();
      await expect(row(screen, p1.id)).toContainText('Zaproponowane');
      expect((await env.clientPos(p1.id)).clientStatus).toBe('AWAITING_DECISION');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B6-02 the analog dialog previews only what the client will see', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, block: 1 });
      await env.send([p1.id]);
      await openStaffTab(env, app, screen);
      await row(screen, p1.id).getByRole('button', 'Zaproponuj analog').tap();
      const dlg = screen.getByTestId('propose-analog-dialog');
      await expect(dlg).toBeVisible();
      await expect(screen.getByTestId('analog-confirm')).toBeDisabled(); // no material chosen yet
      await dlg.getByRole('textbox', 'Cena sprzedaży netto').fill('90');
      await dlg.getByRole('textbox', 'Cena zakupu (wewnętrzna)').fill('59,99');
      await expect(screen.getByTestId('preview-price')).toContainText('90,00');
      const preview = screen.getByTestId('client-preview');
      await expect(preview).not.toContainText('59,99');
      await expect(preview).not.toContainText('Zakup');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B6-03 recording the client decision needs a comment and is marked in the history', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, block: 1 });
      await env.send([p1.id]);
      await openStaffTab(env, app, screen);
      await row(screen, p1.id).getByRole('button', 'Zapisz decyzję klienta').first().tap();
      await expect(screen.getByTestId('decide-on-behalf-dialog')).toBeVisible();
      await expect(screen.getByTestId('behalf-confirm')).toBeDisabled();
      await screen.getByRole('textbox', 'Komentarz').fill('przez telefon');
      await expect(screen.getByTestId('behalf-confirm')).toBeEnabled();
      await screen.getByTestId('behalf-confirm').tap();
      await expect(row(screen, p1.id)).toContainText('Zaakceptowane');
      const c = await env.clientPos(p1.id);
      expect(c.history[0].onBehalf).toBe(true);
      expect(c.history[0].decisionComment).toBe('przez telefon');
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B6-04 client suggestions inbox shows link and comment', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, block: 1 });
      await env.send([p1.id]);
      const link = `https://shop.example/qa-${env.run}`;
      const sg = await env.as('client', 'POST', `${CL}/positions/${p1.id}/suggest`, { link, comment: 'wolę ten' });
      expect(sg.status, sg.text).toBeLessThan(300);
      await openStaffTab(env, app, screen);
      const inbox = screen.getByTestId('suggestions-inbox');
      await expect(inbox).toBeVisible();
      await expect(inbox).toContainText(link);
      await expect(inbox).toContainText('wolę ten');
      await expect(screen.getByTestId('suggestion-propose')).toBeVisible();
      await screen.getByTestId('suggestion-propose').tap();
      await expect(screen.getByTestId('propose-analog-dialog')).toBeVisible();
    } finally {
      await env.cleanup();
    }
  });

  // BUG-5 (FINDINGS.md): the decline dialog labels the reason optional and posts {note:null}, but the API requires a
  // non-blank note (400 error.validation); TC-B6-04 expects the dialog to require a reason.
  test('TC-B6-04 step 2 declining a suggestion requires a reason in the dialog', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, block: 1 });
      await env.send([p1.id]);
      await env.as('client', 'POST', `${CL}/positions/${p1.id}/suggest`, { link: `https://shop.example/qa-${env.run}` });
      await openStaffTab(env, app, screen);
      await screen.getByTestId('suggestion-decline').tap();
      await expect(screen.getByTestId('decline-dialog')).toBeVisible();
      await expect(screen.getByTestId('decline-confirm')).toBeDisabled();
      await screen.getByRole('textbox', 'Powód').fill('brak w sprzedaży');
      await screen.getByTestId('decline-confirm').tap();
      await expect(screen.getByText('Zapisano').first()).toBeVisible();
      await expect(screen.getByTestId('suggestions-inbox')).toHaveCount(0);
    } finally {
      await env.cleanup();
    }
  });

  test('TC-B6-05 the manager gets the action buttons', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, block: 1 });
      await env.send([p1.id]);
      await openStaffTab(env, app, screen, 'manager');
      await expect(row(screen, p1.id).getByRole('button', 'Zaproponuj analog')).toBeVisible();
      await expect(row(screen, p1.id).getByRole('button', 'Zapisz decyzję klienta').first()).toBeVisible();
    } finally {
      await env.cleanup();
    }
  });

  // BUG-6 (FINDINGS.md): BUYER holds POSITION_APPROVALS READ but not PROJECTS READ, so /projects/:id/positionApprovals redirects to /403.
  test('TC-B6-05 step 2 a buyer sees the staff view without action buttons', async ({ app, screen }) => {
    const env = await Env.create();
    try {
      const p1 = await env.position('1', { sale: 100, purchase: 61.37, block: 1 });
      await env.send([p1.id]);
      await openStaffTab(env, app, screen, 'buyer');
      await expect(row(screen, p1.id)).toBeVisible();
      await expect(row(screen, p1.id).getByRole('button', 'Zaproponuj analog')).toBeHidden();
      await expect(row(screen, p1.id).getByRole('button', 'Zapisz decyzję klienta')).toBeHidden();
      await expect(row(screen, p1.id).getByRole('button', 'Wycofaj')).toBeHidden();
    } finally {
      await env.cleanup();
    }
  });
});
