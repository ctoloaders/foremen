import { session } from './shot.mjs';
const s = await session('tomasz@loaders.dev', { mobile: true });
const { page, snap, go } = s;
await go('/deliveries/upcoming');
await snap('for-01-upcoming', { wait: 2000, full: true });
await go('/deliveries/receipt');
await snap('for-02-to-receive', { wait: 2000, full: true });
const card = () => page.locator('div', { hasText: 'Tubądzin Cielo e Terra' }).filter({ has: page.getByRole('button', { name: 'Przyjmij', exact: true }) }).last();
// 1) принято 26 из 28
await page.getByRole('button', { name: 'Przyjmij', exact: true }).first().click();
await page.waitForTimeout(800);
await page.locator('input[inputmode], input[type=number], input[type=text]').first().fill('26');
await page.locator('textarea').fill('26 m² rozładowane, 2 m² z uszkodzonymi narożnikami — do zwrotu');
await snap('for-03-accept-partial', { wait: 800 });
await page.getByRole('button', { name: 'Zapisz' }).click();
await page.waitForTimeout(2000);
await snap('for-04-after-accept', { wait: 800, full: true });
// 2) возврат 2 (рекламация)
await page.getByRole('button', { name: 'Przyjmij', exact: true }).first().click();
await page.waitForTimeout(800);
await page.getByRole('button', { name: 'Zwrot' }).click();
await page.locator('input[inputmode], input[type=number], input[type=text]').first().fill('2');
await page.locator('textarea').fill('Reklamacja: 2 m² płytek z pękniętymi narożnikami, zwrot kurierowi DPD');
await snap('for-05-return-claim', { wait: 800 });
await page.getByRole('button', { name: 'Zapisz' }).click();
await page.waitForTimeout(2000);
await snap('for-06-after-return', { wait: 800, full: true });
// 3) WC — принять всё
await page.getByRole('button', { name: 'Przyjmij wszystko' }).first().click().catch((e) => console.log('accept all', String(e).slice(0, 80)));
await page.waitForTimeout(2000);
await snap('for-07-after-accept-all', { wait: 800, full: true });
await page.getByRole('tab', { name: 'Historia' }).click().catch(() => page.getByText('Historia').first().click());
await snap('for-08-history', { wait: 1800, full: true });
await s.close();
