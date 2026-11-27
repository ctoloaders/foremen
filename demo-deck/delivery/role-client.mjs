import { session } from './shot.mjs';
const s = await session('oleg@loaders.dev');
const { page, snap, go } = s;
await go('/projects/2/positionApprovals');
await snap('cli-01-approvals', { wait: 2000 });
// принять Eurosmart
await page.getByRole('button', { name: 'Akceptuję' }).first().click();
await snap('cli-02-accept-dialog');
await page.getByRole('dialog').getByRole('button', { name: 'Akceptuję' }).click().catch(async () => {
  await page.locator('[role=alertdialog] button', { hasText: 'Akceptuję' }).click();
});
await page.waitForTimeout(1500);
await snap('cli-03-after-accept');
// "Kupuję sam" - диалог
await page.getByRole('button', { name: 'Kupię samodzielnie' }).first().click();
await snap('cli-04-buy-myself-dialog');
await page.keyboard.press('Escape');
await page.waitForTimeout(500);
// отклонить Logis с комментарием
await page.getByRole('button', { name: 'Odrzucam' }).first().click();
await page.waitForTimeout(600);
const ta = page.locator('textarea').first();
await ta.fill('Za drogo — proszę o tańszy analog w podobnym stylu.');
await snap('cli-05-reject-dialog');
await page.locator('[role=dialog] button, [role=alertdialog] button').filter({ hasText: /Odrzuć|Odrzucam|Wyślij|Potwierdź/ }).last().click();
await page.waitForTimeout(1500);
await snap('cli-06-after-reject');
await page.getByRole('button', { name: 'Zaakceptowane' }).click();
await snap('cli-07-accepted-tab', { wait: 1800 });
// проформы клиента
await page.getByRole('tab', { name: /Proformy/ }).click().catch(async () => { await page.getByText('Proformy').first().click(); });
await snap('cli-08-proformas-tab', { wait: 2000 });
await s.close();
