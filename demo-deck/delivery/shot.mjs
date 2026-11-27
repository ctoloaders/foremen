import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
const require = createRequire('/Users/alexander.borohov/projects/loaders/foremen/foremen-qa-auto/e2e/package.json');
const { chromium } = require('playwright-core');
export const OUT = new URL('./shots/', import.meta.url).pathname;
fs.mkdirSync(OUT, { recursive: true });
const EXE = path.join(os.homedir(), 'Library/Caches/ms-playwright/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing');

/** Открывает браузер, логинится реальной формой, выставляет рабочий проект. */
export async function session(email, { mobile = false, projectId = 2 } = {}) {
  const browser = await chromium.launch({ executablePath: EXE, headless: true });
  const context = await browser.newContext(
    mobile
      ? { viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true, locale: 'pl-PL' }
      : { viewport: { width: 1920, height: 1080 }, locale: 'pl-PL' },
  );
  const page = await context.newPage();
  await page.goto('http://localhost:3000/login');
  await page.getByRole('textbox', { name: 'E-mail' }).fill(email);
  await page.getByRole('textbox', { name: 'Hasło' }).fill('alejano123');
  await page.getByRole('button', { name: 'Zaloguj się', exact: true }).click();
  await page.waitForURL((u) => !u.pathname.includes('/login'), { timeout: 20000 });
  await page.evaluate((id) => localStorage.setItem('foremen.workingProjectId', String(id)), projectId);
  const snap = async (name, opts = {}) => {
    await page.waitForTimeout(opts.wait ?? 1200);
    const file = path.join(OUT, `${name}.png`);
    await page.screenshot({ path: file, fullPage: opts.full ?? false });
    console.log('shot', name);
    return file;
  };
  const go = async (p) => { await page.goto('http://localhost:3000' + p); await page.waitForLoadState('networkidle').catch(() => {}); };
  return { browser, context, page, snap, go, close: () => browser.close() };
}
