import { otpSignInUi } from '../../support/client-otp.js';
import { get, rowsOf, type Tag, type World } from './support.js';
// FOR-11-06 Part B helpers (UI). The world, cleanup and API fixtures come from support.ts.

/** Signs in through the login form (PL UI); waits until the login form is gone. */
export async function uiLogin(app: any, screen: any, user: { email: string; password: string }): Promise<void> {
  if ((user as any).roleCode === 'CLIENT') { await otpSignInUi(app, screen, user.email, 'pl'); return; }
  await app.open('/login');
  await screen.getByRole('textbox', 'E-mail').fill(user.email);
  await screen.getByRole('textbox', 'Hasło').fill(user.password);
  await screen.getByRole('button', 'Zaloguj się').tap();
  await screen.getByRole('heading', 'Logowanie').waitFor({ state: 'hidden', timeout: 15000 });
}

/** The working project is client-side state; a narrow viewport opens the navigation drawer on load (FOR-11-04 BUG-7). */
export async function setWorkingProject(browser: any, projectId: number): Promise<void> {
  await browser.evaluate((id: number) => localStorage.setItem('foremen.workingProjectId', String(id)), projectId);
}

export async function closeMenu(screen: any): Promise<void> {
  const close = screen.getByRole('button', 'Close menu', { visible: true });
  if (await close.isVisible()) await close.tap();
}

/** Opens `path` as the signed-in user with `projectId` as the working project. */
export async function openPath(app: any, screen: any, browser: any, projectId: number, path: string): Promise<void> {
  await setWorkingProject(browser, projectId);
  await app.open(path);
  await closeMenu(screen);
}

/** Opens the project workspace tab, e.g. `clientPayments`, `positionApprovals`. */
export const openTab = (app: any, screen: any, browser: any, w: World, tab: string, query = '') =>
  openPath(app, screen, browser, w.projectId, `/projects/${w.projectId}/${tab}${query}`);

/** Signs in as `tag` and opens a workspace tab. */
export async function loginAndOpenTab(app: any, screen: any, browser: any, w: World, tag: Tag, tab: string, query = ''): Promise<void> {
  await uiLogin(app, screen, w.users[tag]!);
  await openTab(app, screen, browser, w, tab, query);
}

/** Staff unified list of the run's project as the API returns it (source of truth for the UI row set). */
export async function apiRows(w: World, tag: Tag | 'ADMIN', query = ''): Promise<any[]> {
  const r = await get(`/api/client-payments?projectId=${w.projectId}&size=100${query ? `&${query}` : ''}`, w.t(tag));
  if (r.status !== 200) throw new Error(`GET /api/client-payments -> ${r.status} ${r.text.slice(0, 200)}`);
  return rowsOf(r);
}

/** The body text of the page (for "no raw key / no secret" checks). */
export const pageText = (browser: any): Promise<string> => browser.evaluate(() => document.body.innerText);
/** A buyer-added position has no purchase request, so the BUYER default board filter "Moje" hides it: switch to "Wszyscy". */
export async function boardAllBuyers(screen: any): Promise<void> {
  await screen.getByRole('combobox', 'Zakupowiec').tap();
  await screen.getByRole('option', 'Wszyscy').tap();
}
