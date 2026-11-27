import { describe, test } from '@e2e-dev/web';
import { expect } from 'e2e';
import {
  completeConnection,
  connectionIds,
  deleteConnectionRow,
  insertConnection,
  listConnections,
  removeConnection,
  restoreSandbox,
  runId,
  sandboxConnectionId,
  tryCreateFintableConnection,
  waitForSyncRun,
} from '../support/bank';
// FOR-07-04a Fintable, Part B (UI) against the real Fintable API and its Plaid sandbox.
// Source: .kiro/specs/FOR-07-company-wide/FOR-07-04a-fintable-integration/test-cases.md.
// Needs the backend started with FINTABLE_ENABLED=true and SALTEDGE_ENABLED=false (see README).
// Repeatability: rows are named FT-<run-id> / SE-<run-id> and removed in each test's finally.
const PKO = '31442786_pko-bank-polski-business-and-personal';
type Screen = Parameters<Parameters<typeof test>[1]>[0]['screen'];
type App = Parameters<Parameters<typeof test>[1]>[0]['app'];
/** Opens Add bank on the Connections tab and waits for the dialog. */
async function openAddBank(app: App, screen: Screen): Promise<void> {
  await app.open('/bank/connections');
  await screen.getByTestId('bank-add').tap();
  await expect(screen.getByTestId('add-bank-dialog')).toBeVisible();
}
/** Opens Add bank and picks Fintable explicitly (it is also the default, TC-B-1 step 1). */
async function openAddBankWithFintable(app: App, screen: Screen): Promise<void> {
  await openAddBank(app, screen);
  await screen.getByRole('combobox', 'Dostawca').tap();
  await screen.getByRole('option', 'Fintable').tap();
  await expect(screen.getByTestId('bank-picker-popular')).toBeVisible({ timeout: 20000 });
}
/** Deletes every connection created since `before` (link attempts that went through). */
async function removeCreatedSince(before: Set<number>): Promise<void> {
  for (const id of await connectionIds()) if (!before.has(id)) await removeConnection(id);
}

describe('FOR-07-04a Add bank with Fintable', { tags: ['for-07-04a', 'fintable'], session: 'admin' }, () => {
  test('TC-B-1 step 1: the dialog preselects Fintable as the provider', async ({ app, screen }) => {
    await openAddBank(app, screen);

    await expect(screen.getByRole('combobox', 'Dostawca')).toHaveText('Fintable');
  });

  test('TC-B-1 steps 1-2: Fintable shows its notes, the attribution and the popular banks', async ({ app, screen }) => {
    await openAddBankWithFintable(app, screen);

    await expect(screen.getByTestId('add-bank-fintable-notes')).toBeVisible();
    await expect(screen.getByRole('link', 'fintable.io')).toHaveAttribute('href', 'https://fintable.io');
    await expect(screen.getByRole('link', 'fintable.io')).toHaveAttribute('target', '_blank');
    await expect(screen.getByTestId('bank-picker-option').first()).toBeVisible();
  });

  test('TC-B-1 step 3: a query under 3 characters keeps the popular banks', async ({ app, screen }) => {
    await openAddBankWithFintable(app, screen);

    await screen.getByTestId('bank-picker-search').fill('pk');

    await expect(screen.getByTestId('bank-picker-search-hint')).toBeVisible();
    await expect(screen.getByTestId('bank-picker-popular')).toBeVisible();
  });

  test('TC-B-1 step 4: searching PKO lists PKO banks with the backing provider', async ({ app, screen }) => {
    await openAddBankWithFintable(app, screen);

    await screen.getByTestId('bank-picker-search').fill('PKO');

    await expect(screen.getByTestId('bank-picker-popular')).toBeHidden({ timeout: 15000 });
    await expect(screen.getByTestId('bank-picker-option').first()).toContainText('PKO Bank Polski');
    // provider-preference ENABLEBANKING,NORDIGEN puts the Enable Banking variant first
    await expect(screen.getByTestId('bank-picker-via').first()).toHaveText('Przez: ENABLEBANKING');
  });

  test('TC-B-1 step 5: Go to bank redirects to the Fintable link page', async ({ app, screen, browser }) => {
    const before = await connectionIds();
    try {
      await openAddBankWithFintable(app, screen);
      await screen.getByTestId('bank-picker-search').fill('PKO');
      await screen.getByTestId('bank-picker-option').first().tap();
      await expect(screen.getByTestId('add-bank-selected')).toBeVisible();

      await screen.getByTestId('add-bank-continue').tap();

      await browser.waitForURL(/^https:\/\/fintable\.io\/api-link\//, { timeout: 30000 });
    } finally {
      await removeCreatedSince(before);
    }
  });

  test('TC-B-1 step 6: a pending Fintable connection offers Finish and Continue', async ({ app, screen }) => {
    const name = 'FT-' + runId() + '-6';
    const id = insertConnection('FINTABLE', 'PENDING_AUTH', name, PKO);
    try {
      await app.open('/bank/connections');
      const row = screen.getByRole('row').filter({ has: screen.getByRole('cell', name) });

      await expect(row.getByTestId('bank-connection-status')).toHaveText('Oczekuje autoryzacji');
      await expect(row.getByRole('button', 'Zakończ połączenie')).toBeEnabled();
      await expect(screen.getByTestId('bank-connection-continue-' + id)).toBeEnabled();
    } finally {
      deleteConnectionRow(id);
    }
  });

  test('TC-B-1 step 7: Finish before the bank login says the connection is not found yet', async ({ app, screen }) => {
    const name = 'FT-' + runId() + '-7';
    const id = insertConnection('FINTABLE', 'PENDING_AUTH', name, PKO);
    try {
      await app.open('/bank/connections');
      await screen.getByTestId('bank-connection-finish-' + id).tap();

      const notFound = 'Połączenie nie zostało jeszcze znalezione w Fintable. Ukończ logowanie w banku i spróbuj ponownie.';
      await expect(screen.getByText(notFound)).toBeVisible({ timeout: 30000 });
      await expect(screen.getByTestId('bank-connection-finish-' + id)).toBeVisible();
    } finally {
      deleteConnectionRow(id);
    }
  });

  test('TC-B-1 step 8: a second link within a minute shows the cooldown', async ({ app, screen }) => {
    const before = await connectionIds();
    try {
      // Arrange: take the one-link-per-minute slot through the API.
      await tryCreateFintableConnection(PKO, 'FT-' + runId() + '-8');
      await openAddBankWithFintable(app, screen);
      await screen.getByTestId('bank-picker-search').fill('PKO');
      await screen.getByTestId('bank-picker-option').first().tap();

      await screen.getByTestId('add-bank-continue').tap();

      await expect(screen.getByTestId('add-bank-error')).toBeVisible({ timeout: 30000 });
      await expect(screen.getByTestId('add-bank-cooldown')).toBeVisible();
      await expect(screen.getByTestId('add-bank-continue')).toBeDisabled();
    } finally {
      await removeCreatedSince(before);
    }
  });
});

describe('FOR-07-04a Salt Edge switched off', { tags: ['for-07-04a', 'fintable'], session: 'admin' }, () => {
  test('TC-B-2 step 1: a Salt Edge connection carries the provider-disabled badge', async ({ app, screen }) => {
    const name = 'SE-' + runId() + '-1';
    const id = insertConnection('SALT_EDGE', 'ACTIVE', name);
    try {
      await app.open('/bank/connections');
      const row = screen.getByRole('row').filter({ has: screen.getByRole('cell', name) });

      await expect(row.getByTestId('bank-connection-provider-disabled')).toHaveText('Dostawca wyłączony');
    } finally {
      deleteConnectionRow(id);
    }
  });

  test('TC-B-2 step 2: refresh and sync are disabled, rename and disable stay available', async ({ app, screen }) => {
    const id = insertConnection('SALT_EDGE', 'ACTIVE', 'SE-' + runId() + '-2');
    try {
      await app.open('/bank/connections');

      await expect(screen.getByTestId('bank-connection-refresh-' + id)).toBeDisabled();
      await expect(screen.getByTestId('bank-connection-sync-' + id)).toBeDisabled();
      await expect(screen.getByTestId('bank-connection-rename-' + id)).toBeEnabled();
      await expect(screen.getByTestId('bank-connection-disable-' + id)).toBeEnabled();
    } finally {
      deleteConnectionRow(id);
    }
  });

  test('TC-B-2 step 3: Add bank does not offer Salt Edge', async ({ app, screen }) => {
    await openAddBank(app, screen);

    await screen.getByRole('combobox', 'Dostawca').tap();

    await expect(screen.getByRole('option', 'Fintable')).toBeVisible();
    await expect(screen.getByRole('option', 'Salt Edge')).toHaveCount(0);
  });
});

describe('FOR-07-04a Supported banks with Fintable', { tags: ['for-07-04a', 'fintable'], session: 'admin' }, () => {
  test('TC-B-4 step 1: the tab opens on Fintable with a search box and popular banks', async ({ app, screen }) => {
    await app.open('/bank/supported-banks');

    await expect(screen.getByRole('combobox', 'Dostawca')).toHaveValue('FINTABLE');
    await expect(screen.getByTestId('bank-supported-search')).toBeVisible();
    await expect(screen.getByTestId('bank-supported-popular')).toBeVisible({ timeout: 20000 });
  });

  test('TC-B-4 step 2: searching mBank lists the mBank variants', async ({ app, screen }) => {
    await app.open('/bank/supported-banks');
    await expect(screen.getByTestId('bank-supported-popular')).toBeVisible({ timeout: 20000 });

    await screen.getByTestId('bank-supported-search').fill('mBank');

    await expect(screen.getByTestId('bank-supported-popular')).toBeHidden({ timeout: 15000 });
    await expect(screen.getByRole('button', 'Połącz: mBank Corporate')).toBeVisible();
    await expect(screen.getByRole('button', 'Połącz: mBank Retail').first()).toBeVisible();
    await expect(screen.getByRole('button', 'Połącz: mBank (Business and Personal)').first()).toBeVisible();
    // BUG-3: only PL groups; the US-only 'mBank - Business' (AKOYA) group is filtered out.
    await expect(screen.getByRole('button', 'Połącz: mBank - Business')).toHaveCount(0);
  });

  test('TC-B-4 step 3: a query with no match shows the empty state', async ({ app, screen }) => {
    await app.open('/bank/supported-banks');
    await expect(screen.getByTestId('bank-supported-popular')).toBeVisible({ timeout: 20000 });

    await screen.getByTestId('bank-supported-search').fill('zzzzzz');

    await expect(screen.getByTestId('bank-supported-empty')).toHaveText('Nie znaleziono banków dla tego zapytania', { timeout: 15000 });
  });
});

/** The Foremen connection bound to the Fintable sandbox (shared by TC-B-3), created when missing. */
async function sandboxConnection(): Promise<number> {
  const sandbox = await sandboxConnectionId();
  if (!sandbox) throw new Error('No Fintable sandbox: set FINTABLE_API_TOKEN, see TC-A-8 step 5');
  const all = await listConnections();
  const bound = all.find((c) => c.externalConnectionId === sandbox && c.status === 'ACTIVE');
  if (bound) return bound.id;
  const id = insertConnection('FINTABLE', 'PENDING_AUTH', 'FT-sandbox-' + runId(), 'test_sandbox');
  await completeConnection(id, { connection_id: sandbox, status: 'connected' });
  const after = (await listConnections()).find((c) => c.id === id);
  if (after?.status !== 'ACTIVE') {
    deleteConnectionRow(id);
    throw new Error('complete did not bind the sandbox, status ' + after?.status);
  }
  const run = await waitForSyncRun(id);
  if (run !== 'SUCCEEDED') throw new Error('first sync of the sandbox ended ' + run);
  return id;
}

describe('FOR-07-04a Fintable sandbox connection', { tags: ['for-07-04a', 'fintable'], session: 'admin' }, () => {
  test('TC-B-3 step 1: the sandbox connection is active', async ({ app, screen }) => {
    const id = await sandboxConnection();

    await app.open('/bank/connections');

    const refresh = screen.getByTestId('bank-connection-refresh-' + id);
    const row = screen.getByRole('row').filter({ has: refresh });
    await expect(row.getByTestId('bank-connection-status')).toHaveText('Aktywne');
  });

  test('TC-B-3 step 2: the Accounts tab lists the 14 sandbox accounts in USD', async ({ app, screen }) => {
    const id = await sandboxConnection();
    const name = (await listConnections()).find((c) => c.id === id)!.displayName;

    await app.open('/bank/accounts');

    const heading = screen.getByRole('heading', 'Sandbox Testing Bank ' + name);
    const group = screen.getByTestId('bank-account-group').filter({ has: heading });
    await expect(group.getByRole('row')).toHaveCount(15); // header + 14 accounts
    await expect(group.getByRole('cell', 'USD')).toHaveCount(14);
  });

  test('TC-B-3 step 3: a second manual refresh is refused with the Fintable limit', async ({ app, screen }) => {
    const id = await sandboxConnection();
    await app.open('/bank/connections');
    await screen.getByTestId('bank-connection-refresh-' + id).tap();

    await screen.getByTestId('bank-connection-refresh-' + id).tap();

    // The toast carries a time, so match its fixed part.
    const toast = screen.getByRole('listitem').last();
    await expect(toast).toContainText('Fintable ogranicza liczbę ręcznych odświeżeń', { timeout: 15000 });
  });

  // Runs last: disabling deletes the sandbox in Fintable, so the finally recreates it.
  test('TC-B-3 step 4: disabling warns about the Fintable purge', async ({ app, screen }) => {
    const id = await sandboxConnection();
    try {
      await app.open('/bank/connections');
      await screen.getByTestId('bank-connection-disable-' + id).tap();
      await expect(screen.getByTestId('bank-disable-fintable-warning')).toBeVisible();

      await screen.getByTestId('bank-disable-confirm').tap();

      const row = screen.getByRole('row').filter({ has: screen.getByTestId('bank-connection-delete-' + id) });
      await expect(row.getByTestId('bank-connection-status')).toHaveText('Wyłączone', { timeout: 30000 });
    } finally {
      await removeConnection(id);
      await restoreSandbox();
    }
  });
});
